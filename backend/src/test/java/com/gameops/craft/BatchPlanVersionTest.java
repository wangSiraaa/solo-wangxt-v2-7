package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.domain.CraftPlanUnit;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.repo.RecipeRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanUnitTxService;
import com.gameops.craft.service.RecipeAdminService;
import com.gameops.craft.support.PlanTestDriver;
import com.gameops.craft.support.TestDataResetter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * A plan freezes recipe version + activity window at creation: units already
 * pre-occupied always finish on the bound version; units not yet started stop
 * once the activity ends (window) or the recipe is closed. Publishing a new
 * version mid-run never re-binds pending units to the new version.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.plan.worker.enabled=false",
        "app.plan.lease-seconds=1"
})
class BatchPlanVersionTest {

    @Autowired private PlanService plans;
    @Autowired private PlanUnitTxService unitTx;
    @Autowired private RecipeAdminService admin;
    @Autowired private RecipeRepository recipes;
    @Autowired private PlanRepository planRepo;
    @Autowired private InventoryRepository inventory;
    @Autowired private PlanUnitRepository unitRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestDataResetter resetter;
    @Autowired private PlanTestDriver driver;

    private static final long PLAYER3 = 4L;
    private static final long THUNDER_BOW = 2L; // v1: 2 wood + 1 core -> EQP_THUNDER_BOW

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    private CraftPlan create(int count) {
        Map<String, Object> body =
                plans.createPlan(PLAYER3, THUNDER_BOW, count, UUID.randomUUID().toString());
        return planRepo.findByNo((String) body.get("planNo")).orElseThrow();
    }

    private void grant(long qtyWood, long qtyCore) {
        inventory.upsertBalance(PLAYER3, "MAT_WOOD", qtyWood, Instant.now());
        inventory.upsertBalance(PLAYER3, "MAT_MAGIC_CORE", qtyCore, Instant.now());
    }

    private void runOne(CraftPlan plan, String worker) {
        String token = PlanTestDriver.token();
        var claim = driver.claim(plan, worker, token);
        assertThat(claim.unit()).isNotNull();
        driver.execute(plan, claim, worker, token);
    }

    @Test
    void new_version_published_midrun_does_not_rebind_plan_units() {
        // player3 seed has 4 wood + 1 core; grant enough for 2 v1 bows.
        grant(4, 2);
        CraftPlan plan = create(2);
        assertThat(plan.recipeVersionId()).isEqualTo(2L); // v1 id in seed

        // Publish v2 with different inputs/outputs while plan is queued.
        var nv = admin.newVersion(THUNDER_BOW);
        long draftId = ((Number) nv.get("draftVersionId")).longValue();
        admin.editDraft(draftId, new RecipeAdminService.VersionSpec(
                List.of(new ItemQty("MAT_WOOD", 4)),
                List.of(new ItemQty("EQP_THUNDER_BOW_PLUS", 1)),
                "2026-09-01T00:00:00Z", "2026-12-31T23:59:59Z", 120));
        admin.publish(THUNDER_BOW, draftId, 1L);
        assertThat(recipes.findCurrentPublished(THUNDER_BOW).orElseThrow().versionNo()).isEqualTo(2);

        runOne(plan, "w1");
        runOne(plan, "w1");

        CraftPlan fin = planRepo.findByNo(plan.planNo()).orElseThrow();
        assertThat(fin.status()).isEqualTo("COMPLETED");
        // v1 outputs, not v2 — the plan froze its version at creation and never
        // re-binds, even though v2 was published before the units ran.
        assertThat(inventory.getQty(PLAYER3, "EQP_THUNDER_BOW")).isEqualTo(2);
        assertThat(inventory.getQty(PLAYER3, "EQP_THUNDER_BOW_PLUS")).isZero();
    }

    @Test
    void activity_end_stops_new_units_but_preoccupied_unit_finishes_on_bound_version() {
        grant(6, 3); // 3 bows worth
        CraftPlan plan = create(3);

        // Unit 1 leased and deducted while the activity window is open.
        String token = PlanTestDriver.token();
        var claim1 = driver.claim(plan, "w1", token);
        assertThat(claim1.unit().unitNo()).isEqualTo(1);
        unitTx.deduct(claim1.unit().id(), token);

        // Activity ends (move the frozen v1 window end into the past).
        jdbc.update("UPDATE recipe_version SET end_time = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(10)), plan.recipeVersionId());

        // The pre-occupied unit still completes on the bound snapshot.
        unitTx.reward(claim1.unit().id(), token);
        unitTx.writeBackDone(claim1.unit().id(), token);

        // Next claim must NOT start unit 2: gate stops the plan, units 2&3 skipped.
        var claim2 = driver.claim(plan, "w1", PlanTestDriver.token());
        assertThat(claim2.unit()).isNull();
        assertThat(claim2.stopReason()).contains("ACTIVITY_ENDED");
        var claim3 = driver.claim(plan, "w1", PlanTestDriver.token());
        assertThat(claim3.unit()).isNull();

        plans.maybeFinalize(plan.id(), Instant.now());
        CraftPlan fin = planRepo.findByNo(plan.planNo()).orElseThrow();
        assertThat(fin.status()).isEqualTo("PARTIAL");
        assertThat(fin.stopReason()).isEqualTo("ACTIVITY_ENDED");
        assertThat(fin.completedCount()).isEqualTo(1);
        assertThat(fin.skippedCount()).isEqualTo(2);
        List<CraftPlanUnit> units = unitRepo.listByPlan(plan.id());
        assertThat(units.get(0).status()).isEqualTo("DONE");
        assertThat(units.get(1).status()).isEqualTo("SKIPPED");
        assertThat(units.get(2).status()).isEqualTo("SKIPPED");
        assertThat(inventory.getQty(PLAYER3, "EQP_THUNDER_BOW")).isEqualTo(1);
        // Skipped units never touched materials.
        assertThat(inventory.getQty(PLAYER3, "MAT_WOOD")).isEqualTo(4);
        assertThat(inventory.getQty(PLAYER3, "MAT_MAGIC_CORE")).isEqualTo(2);
    }

    @Test
    void recipe_closed_after_plan_created_stops_fresh_units() {
        grant(4, 2);
        CraftPlan plan = create(2);
        admin.close(THUNDER_BOW, "activity ended");

        var claim = driver.claim(plan, "w1", PlanTestDriver.token());
        assertThat(claim.unit()).isNull();
        assertThat(claim.stopReason()).contains("RECIPE_CLOSED");
        plans.maybeFinalize(plan.id(), Instant.now());
        CraftPlan fin = planRepo.findByNo(plan.planNo()).orElseThrow();
        // Nothing delivered, nothing failed technically: PARTIAL with zero done;
        // counters explicitly record completed=0 / not-executed=2.
        assertThat(fin.status()).isEqualTo("PARTIAL");
        assertThat(fin.completedCount()).isZero();
        assertThat(fin.skippedCount()).isEqualTo(2);
    }
}
