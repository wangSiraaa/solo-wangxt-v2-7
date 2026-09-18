package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanWorker;
import com.gameops.craft.service.RecipeAdminService;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.support.TestDataResetter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Happy path + plan lifecycle: snapshot-bound execution, counts, new version mid-run, shortage. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.sweeper.delay-ms=600000",
        "app.plan-worker.delay-ms=600000"
})
class BatchPlanFlowTest {

    @Autowired private PlanService planService;
    @Autowired private PlanWorker worker;
    @Autowired private RecipeAdminService admin;
    @Autowired private InventoryRepository inventory;
    @Autowired private BatchPlanRepository plans;
    @Autowired private TestDataResetter resetter;

    // player4 seed: iron 9, core 6, shard 3 -> FIRE_SWORD v1 costs 3/2/1 => exactly 3 crafts.
    private static final long PLAYER = 4L;
    private static final int RECIPE = 1;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    @Test
    void plan_runs_all_units_on_snapshot_and_records_completed() {
        var created = planService.createPlan(PLAYER, RECIPE, 3, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");
        assertThat(created.get("totalUnits")).isEqualTo(3);
        assertThat(created.get("boundVersionNo")).isEqualTo(1);

        worker.runOnce();

        var detail = planService.planDetail(PLAYER, planNo);
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
        assertThat(detail.get("completedCount")).isEqualTo(3);
        assertThat(detail.get("notRunCount")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) detail.get("units");
        assertThat(units).hasSize(3);
        assertThat(units).allSatisfy(u -> {
            assertThat(u.get("status")).isEqualTo("DONE");
            assertThat(u.get("orderNo")).asString().startsWith("CU");
        });

        // 3 swords + 300 gold; materials fully consumed (9/6/3).
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER, "GOLD")).isEqualTo(500 + 300);
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isZero();
        assertThat(inventory.getQty(PLAYER, "MAT_MAGIC_CORE")).isZero();
        assertThat(inventory.getQty(PLAYER, "MAT_FIRE_SHARD")).isZero();

        // Final plan row terminal with explicit counts.
        var row = plans.findByNo(planNo).orElseThrow();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completedCount()).isEqualTo(3);
        assertThat(row.failedCount()).isZero();
        assertThat(row.skippedCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void new_version_published_while_running_does_not_change_bound_snapshot() throws Exception {
        // Create a 2-unit plan but let the worker only run it after v2 is published.
        var created = planService.createPlan(PLAYER, RECIPE, 2, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");

        // Publish v2 with a DIFFERENT output and cost while the plan exists (not yet executed).
        var nv = admin.newVersion(RECIPE);
        long draftId = ((Number) nv.get("draftVersionId")).longValue();
        admin.editDraft(draftId, new RecipeAdminService.VersionSpec(
                List.of(new ItemQty("MAT_IRON", 1)),
                List.of(new ItemQty("EQP_FIRE_SWORD_PLUS", 1)),
                "2026-09-01T00:00:00Z", "2026-12-31T23:59:59Z", 120));
        admin.publish(RECIPE, draftId, 1L);

        worker.runOnce();

        var detail = planService.planDetail(PLAYER, planNo);
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
        assertThat(detail.get("boundVersionNo")).isEqualTo(1);
        // Bound v1 outputs, not v2.
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(2);
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD_PLUS")).isZero();
        // v1 material cost 3/2/1 x2 -> iron 3 left, core 2 left, shard 1 left.
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER, "MAT_MAGIC_CORE")).isEqualTo(2);
        assertThat(inventory.getQty(PLAYER, "MAT_FIRE_SHARD")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void last_set_of_materials_produces_partial_plan_and_no_negative_balance() {
        // Player can afford exactly 3 of 5 requested units.
        var created = planService.createPlan(PLAYER, RECIPE, 5, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");

        worker.runOnce();
        worker.runOnce(); // ensure finalize pass after the shortage freeze

        var detail = planService.planDetail(PLAYER, planNo);
        assertThat(detail.get("status")).isEqualTo("PARTIAL");
        assertThat(detail.get("completedCount")).isEqualTo(3);
        int skipped = ((Number) detail.get("skippedCount")).intValue();
        assertThat(skipped).isEqualTo(2);
        assertThat(detail.get("notRunCount")).isEqualTo(0);

        // No negative balances; all materials used by the 3 completed units.
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isZero();
        assertThat(inventory.getQty(PLAYER, "MAT_MAGIC_CORE")).isZero();
        assertThat(inventory.getQty(PLAYER, "MAT_FIRE_SHARD")).isZero();
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) detail.get("units");
        long done = units.stream().filter(u -> "DONE".equals(u.get("status"))).count();
        long skip = units.stream().filter(u -> "SKIPPED".equals(u.get("status"))).count();
        assertThat(done).isEqualTo(3);
        assertThat(skip).isEqualTo(2);
    }

    @Test
    void plan_size_outside_1_100_is_rejected() {
        try {
            planService.createPlan(PLAYER, RECIPE, 0, UUID.randomUUID().toString());
            throw new AssertionError("expected rejection");
        } catch (com.gameops.craft.common.ApiException e) {
            assertThat(e.getCode()).isEqualTo("PLAN_SIZE_INVALID");
        }
        try {
            planService.createPlan(PLAYER, RECIPE, 101, UUID.randomUUID().toString());
            throw new AssertionError("expected rejection");
        } catch (com.gameops.craft.common.ApiException e) {
            assertThat(e.getCode()).isEqualTo("PLAN_SIZE_INVALID");
        }
    }

    @Test
    void same_idempotency_key_replays_the_same_plan() {
        String key = UUID.randomUUID().toString();
        var a = planService.createPlan(PLAYER, RECIPE, 2, key);
        var b = planService.createPlan(PLAYER, RECIPE, 2, key);
        assertThat(b.get("planNo")).isEqualTo(a.get("planNo"));
        assertThat(b.get("replayed")).isEqualTo(true);
        // Only two units worth of materials will ever be consumed.
        worker.runOnce();
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(2);
    }
}
