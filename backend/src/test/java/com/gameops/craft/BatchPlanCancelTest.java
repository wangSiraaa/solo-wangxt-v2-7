package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.domain.BatchPlan;
import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanTxService;
import com.gameops.craft.service.PlanWorker;
import com.gameops.craft.service.RecipeAdminService;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.support.TestDataResetter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Cancel/claim race: cancel only removes not-started units; deducted units finish and their
 * rewards are never rolled back. Activity end stops new sequence numbers while bound units run.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.sweeper.delay-ms=600000",
        "app.plan-worker.delay-ms=600000"
})
class BatchPlanCancelTest {

    private static final Instant T = Instant.parse("2026-09-17T10:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        static final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(T);
        @Bean @Primary
        Clock testClock() {
            return new Clock() {
                public Instant instant() { return now.get(); }
                public ZoneOffset getZone() { return ZoneOffset.UTC; }
                public Clock withZone(java.time.ZoneId zone) { return this; }
            };
        }
    }

    @Autowired private PlanService planService;
    @Autowired private PlanTxService tx;
    @Autowired private PlanWorker worker;
    @Autowired private BatchPlanRepository plans;
    @Autowired private PlanUnitRepository units;
    @Autowired private InventoryRepository inventory;
    @Autowired private TestDataResetter resetter;

    private static final long PLAYER = 4L;
    private static final int RECIPE = 1; // window ends 2026-10-31

    @BeforeEach
    void reset() {
        resetter.reset();
        FixedClockConfig.now.set(T);
    }

    @Test
    void cancel_before_any_worker_skips_every_unit_and_keeps_materials() {
        var created = planService.createPlan(PLAYER, RECIPE, 3, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");

        var cancelled = planService.cancel(PLAYER, planNo, "changed my mind");
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.get("skippedCount")).isEqualTo(3);
        assertThat(cancelled.get("completedCount")).isEqualTo(0);

        worker.runOnce(); // must not resurrect anything

        var plan = plans.findByNo(planNo).orElseThrow();
        assertThat(plan.status()).isEqualTo("CANCELLED");
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(9);
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancel_races_claim_only_unstarted_units_are_cancelled_rewards_kept() throws Exception {
        var created = planService.createPlan(PLAYER, RECIPE, 3, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");
        var plan = plans.findByNo(planNo).orElseThrow();
        PlanUnit first = units.listByPlan(plan.id()).get(0);

        // Worker A claims and fully finishes unit 1 (durable DEDUCTED stage at least).
        Boolean acquired = tx.acquireUnit(first, "worker-A");
        assertThat(acquired).isTrue();
        PlanUnit claimed = units.findByIdForUpdate(first.id()).orElseThrow();
        tx.deductMaterials(claimed); // materials gone for unit 1
        PlanUnit deducted = units.findByIdForUpdate(first.id()).orElseThrow();

        // Player hits cancel CONCURRENTLY while unit 1 is DEDUCTED (reward not yet issued).
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> cancelResult = new AtomicReference<>();
        var f1 = pool.submit(() -> {
            try {
                start.await();
                cancelResult.set(planService.cancel(PLAYER, planNo, "race cancel"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var f2 = pool.submit(() -> {
            try {
                start.await();
                // worker A continues its pipeline on the already-deducted unit
                tx.issueRewards(deducted);
                tx.writeBackDone(deducted);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Unit 1 reward is NOT rolled back; units 2,3 were never started -> skipped.
        var detail = planService.planDetail(PLAYER, planNo);
        assertThat(detail.get("status")).isEqualTo("PARTIAL");
        assertThat(detail.get("completedCount")).isEqualTo(1);
        assertThat(detail.get("skippedCount")).isEqualTo(2);
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(1);
        assertThat(inventory.getQty(PLAYER, "GOLD")).isEqualTo(600);
        // Materials for the completed unit stay consumed; skipped units never touched theirs.
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(9 - 3);
        assertThat(inventory.getQty(PLAYER, "MAT_MAGIC_CORE")).isEqualTo(6 - 2);
        assertThat(inventory.getQty(PLAYER, "MAT_FIRE_SHARD")).isEqualTo(3 - 1);

        // A late worker pass changes nothing.
        worker.runOnce();
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void activity_end_stops_new_units_but_claimed_units_finish_on_bound_version() {
        // Create a 3-unit plan, claim unit 1, then move the clock beyond the activity window.
        var created = planService.createPlan(PLAYER, RECIPE, 3, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");
        var plan = plans.findByNo(planNo).orElseThrow();
        PlanUnit first = units.listByPlan(plan.id()).get(0);

        // Worker claims unit 1 BEFORE the end (lease taken inside the window check).
        Boolean acquired = tx.acquireUnit(first, "worker-A");
        assertThat(acquired).isTrue();

        // Activity ends.
        FixedClockConfig.now.set(Instant.parse("2026-11-01T00:00:00Z"));

        // Run workers repeatedly. Unit 1 (already started) completes; units 2,3 never start.
        for (int i = 0; i < 5; i++) {
            worker.runOnce();
        }

        var detail = planService.planDetail(PLAYER, planNo);
        assertThat(detail.get("completedCount")).isEqualTo(1);
        int skipped = ((Number) detail.get("skippedCount")).intValue();
        assertThat(skipped).isEqualTo(2);
        assertThat(detail.get("status")).isEqualTo("PARTIAL");
        assertThat(String.valueOf(detail.get("statusReason"))).contains("activity");

        List<Map<String, Object>> unitRows = (List<Map<String, Object>>) detail.get("units");
        assertThat(unitRows.get(0).get("status")).isEqualTo("DONE");
        assertThat(unitRows.get(1).get("status")).isEqualTo("SKIPPED");
        assertThat(unitRows.get(2).get("status")).isEqualTo("SKIPPED");

        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(1);
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(6); // only one set consumed
    }
}
