package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.domain.CraftPlanUnit;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanUnitTxService;
import com.gameops.craft.support.LeaseManipulator;
import com.gameops.craft.support.PlanTestDriver;
import com.gameops.craft.support.TestDataResetter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Batch-plan consistency suite (H2). The scheduled worker is disabled here so
 * every lease/deduction/reward step is driven deterministically by the test;
 * production executes the identical PlanService steps inside PlanWorker.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.plan.worker.enabled=false",
        "app.plan.lease-seconds=1"
})
class BatchPlanFlowTest {

    @Autowired private PlanService plans;
    @Autowired private PlanUnitTxService unitTx;
    @Autowired private PlanRepository planRepo;
    @Autowired private PlanUnitRepository unitRepo;
    @Autowired private InventoryRepository inventory;
    @Autowired private LedgerRepository ledger;
    @Autowired private TestDataResetter resetter;
    @Autowired private PlanTestDriver driver;
    @Autowired private LeaseManipulator leases;

    private static final long PLAYER3 = 4L; // 9 iron / 6 core / 3 shard / 4 wood / 500 gold
    private static final int FIRE_SWORD_PER = 1; // 3 iron + 2 core + 1 shard -> sword + 100 gold
    private static final long RECIPE_FIRE_SWORD = 1L;
    private static final long RECIPE_THUNDER_BOW = 2L; // 2 wood + 1 core -> bow

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    private CraftPlan create(long player, long recipe, int count) {
        Map<String, Object> body =
                plans.createPlan(player, recipe, count, UUID.randomUUID().toString());
        return planRepo.findByNo((String) body.get("planNo")).orElseThrow();
    }

    /** Run one full unit through claim + execute. */
    private void runOne(CraftPlan plan, String worker) {
        String token = PlanTestDriver.token();
        var claim = driver.claim(plan, worker, token);
        assertThat(claim.unit()).as("worker %s should get a unit", worker).isNotNull();
        driver.execute(plan, claim, worker, token);
    }

    private CraftPlan refresh(CraftPlan p) {
        return planRepo.findByNo(p.planNo()).orElseThrow();
    }

    // ------------------------------------------------------------------ (1)

    @Test
    void plan_creation_freezes_snapshot_and_units_but_does_not_deduct() {
        long ironBefore = inventory.getQty(PLAYER3, "MAT_IRON");
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 3);

        assertThat(plan.totalCount()).isEqualTo(3);
        assertThat(plan.status()).isEqualTo("RUNNING");
        List<CraftPlanUnit> units = unitRepo.listByPlan(plan.id());
        assertThat(units).hasSize(3);
        assertThat(units).extracting(CraftPlanUnit::unitNo).containsExactly(1, 2, 3);
        assertThat(units).allMatch(u -> u.status().equals("PENDING"));
        // No material moves until a worker executes a unit.
        assertThat(inventory.getQty(PLAYER3, "MAT_IRON")).isEqualTo(ironBefore);
    }

    @Test
    void count_outside_1_to_100_is_rejected_server_side() {
        assertThat(catchCode(() -> plans.createPlan(PLAYER3, RECIPE_FIRE_SWORD, 0, key())))
                .isEqualTo("PLAN_COUNT_OUT_OF_RANGE");
        assertThat(catchCode(() -> plans.createPlan(PLAYER3, RECIPE_FIRE_SWORD, 101, key())))
                .isEqualTo("PLAN_COUNT_OUT_OF_RANGE");
    }

    // ------------------------------------------------------------------ (2)

    @Test
    void thirty_two_concurrent_creates_with_one_key_make_exactly_one_plan_with_3_units()
            throws Exception {
        String sharedKey = UUID.randomUUID().toString();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> planNos = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Map<String, Object> body =
                            plans.createPlan(PLAYER3, RECIPE_FIRE_SWORD, 3, sharedKey);
                    planNos.add((String) body.get("planNo"));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors.get()).as("all 32 callers get the single plan or its replay").isZero();
        assertThat(planNos).hasSize(32);
        assertThat(planNos).as("every concurrent create resolves to the SAME plan")
                .containsOnly(planNos.get(0));
        // Exactly one header row, exactly 3 units, no materials moved.
        List<CraftPlan> all = planRepo.listRecent(100).stream()
                .filter(p -> p.playerId() == PLAYER3).toList();
        assertThat(all).hasSize(1);
        assertThat(unitRepo.listByPlan(all.get(0).id())).hasSize(3);
        assertThat(inventory.getQty(PLAYER3, "MAT_IRON")).isEqualTo(9);
    }

    // ------------------------------------------------------------------ (3)

    @Test
    void two_workers_claiming_same_unit_only_one_wins_each_unit_done_once() throws Exception {
        // 5 fire-sword crafts worth so all units can complete.
        grant(PLAYER3, "MAT_IRON", 15);
        grant(PLAYER3, "MAT_MAGIC_CORE", 10);
        grant(PLAYER3, "MAT_FIRE_SHARD", 5);
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 5);
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger empty = new AtomicInteger();

        for (int w = 0; w < threads; w++) {
            final String worker = "w" + w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 5; i++) {
                        String token = PlanTestDriver.token();
                        var claim = driver.claim(plan, worker, token);
                        if (claim.unit() != null) {
                            claimed.incrementAndGet();
                            driver.execute(plan, claim, worker, token);
                        } else {
                            empty.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        CraftPlan finished = refresh(plan);
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.completedCount()).isEqualTo(5);
        assertThat(claimed.get()).isEqualTo(5);
        assertThat(unitRepo.listByPlan(plan.id()))
                .as("every unit DONE exactly once")
                .hasSize(5)
                .allMatch(u -> u.status().equals("DONE"))
                .extracting(CraftPlanUnit::orderNo)
                .doesNotHaveDuplicates();
        // Exactly five swords / 500 gold added (100 per craft).
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(5);
        // Exactly five consume + five produce ledger lines per relevant item.
        assertThat(ledger.listByPlanNo(plan.planNo()).stream()
                .filter(l -> "CONSUME".equals(l.entryType())).count()).isEqualTo(15); // 3 inputs
        assertThat(ledger.listByPlanNo(plan.planNo()).stream()
                .filter(l -> "PRODUCE".equals(l.entryType())).count()).isEqualTo(10); // 2 outputs
    }

    // ------------------------------------------------------------------ (4a)

    @Test
    void crash_after_deduct_before_reward_resumes_from_lease_without_double_deduction() {
        // Grant player3 enough for 2 fire swords (6 iron / 4 core / 2 shard).
        grant(PLAYER3, "MAT_IRON", 6);
        grant(PLAYER3, "MAT_MAGIC_CORE", 4);
        grant(PLAYER3, "MAT_FIRE_SHARD", 2);
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 2);

        // Worker A claims unit 1 and performs ONLY deduct (crash window 1 opens).
        String tokenA = PlanTestDriver.token();
        var claimA = driver.claim(plan, "workerA", tokenA);
        assertThat(claimA.unit().unitNo()).isEqualTo(1);
        unitTx.deduct(claimA.unit().id(), tokenA);
        // ... process crashes here: materials gone, no PRODUCE, unit stays LEASED.
        CraftPlanUnit stuck = unitRepo.findByIdForUpdate(claimA.unit().id()).orElseThrow();
        assertThat(stuck.orderNo()).isNotBlank();
        assertThat(stuck.consumedAt()).isNotNull();
        assertThat(stuck.rewardedAt()).isNull();
        long ironMid = inventory.getQty(PLAYER3, "MAT_IRON");
        assertThat(ironMid).isEqualTo(3); // 6 - 3

        // Lease expires; worker B reclaims and runs the full recovery execution.
        leases.expireLease(stuck.id());
        String tokenB = PlanTestDriver.token();
        var claimB = driver.claim(plan, "workerB", tokenB);
        assertThat(claimB.recovered()).isTrue();
        assertThat(claimB.unit().id()).isEqualTo(stuck.id());
        driver.execute(plan, claimB, "workerB", tokenB);

        // Dead worker A's late writeback must be rejected by the lease-token CAS.
        boolean zombie = unitTx.writeBackDone(stuck.id(), tokenA);
        assertThat(zombie).isFalse();

        // Exactly one deduction set + one reward set.
        assertThat(inventory.getQty(PLAYER3, "MAT_IRON")).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(1);
        long consume = ledger.listByPlanNo(plan.planNo()).stream()
                .filter(l -> "CONSUME".equals(l.entryType()) && "MAT_IRON".equals(l.itemCode()))
                .count();
        long produce = ledger.listByPlanNo(plan.planNo()).stream()
                .filter(l -> "PRODUCE".equals(l.entryType()) && "EQP_FIRE_SWORD".equals(l.itemCode()))
                .count();
        assertThat(consume).isEqualTo(1);
        assertThat(produce).isEqualTo(1);
    }

    // ------------------------------------------------------------------ (4b)

    @Test
    void crash_after_reward_before_writeback_completes_unit_on_recovery_without_double_reward() {
        grant(PLAYER3, "MAT_IRON", 3);
        grant(PLAYER3, "MAT_MAGIC_CORE", 2);
        grant(PLAYER3, "MAT_FIRE_SHARD", 1);
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 1);

        String tokenA = PlanTestDriver.token();
        var claimA = driver.claim(plan, "workerA", tokenA);
        unitTx.deduct(claimA.unit().id(), tokenA);
        unitTx.reward(claimA.unit().id(), tokenA);
        // Crash window 2: order COMMITTED, rewards issued, unit row still LEASED.
        long swordBefore = inventory.getQty(PLAYER3, "EQP_FIRE_SWORD");
        long goldBefore = inventory.getQty(PLAYER3, "GOLD");
        assertThat(swordBefore).isEqualTo(1);
        CraftPlanUnit stuck = unitRepo.findByIdForUpdate(claimA.unit().id()).orElseThrow();
        assertThat(stuck.rewardedAt()).isNotNull();
        assertThat(stuck.status()).isEqualTo("LEASED");

        leases.expireLease(stuck.id());
        String tokenB = PlanTestDriver.token();
        var claimB = driver.claim(plan, "workerB", tokenB);
        assertThat(claimB.recovered()).isTrue();
        driver.execute(plan, claimB, "workerB", tokenB); // deduct no-op, reward replay, writeback DONE

        CraftPlanUnit done = unitRepo.listByPlan(plan.id()).get(0);
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(refresh(plan).status()).isEqualTo("COMPLETED");
        // No second reward.
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(1);
        assertThat(inventory.getQty(PLAYER3, "GOLD")).isEqualTo(goldBefore);
        long produce = ledger.listByPlanNo(plan.planNo()).stream()
                .filter(l -> "PRODUCE".equals(l.entryType()) && "EQP_FIRE_SWORD".equals(l.itemCode()))
                .count();
        assertThat(produce).isEqualTo(1);
    }

    // ------------------------------------------------------------------ (5)

    @Test
    void cancel_races_claim_only_unstarted_units_cancel_completed_rewards_kept() {
        // Enough for 3 crafts.
        grant(PLAYER3, "MAT_IRON", 9);
        grant(PLAYER3, "MAT_MAGIC_CORE", 6);
        grant(PLAYER3, "MAT_FIRE_SHARD", 3);
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 3);

        // Worker completes unit 1.
        runOne(plan, "w1");
        // Worker is about to claim unit 2; player cancels concurrently.
        // Simulate interleaving: cancel first, then a late claim.
        Map<String, Object> cancelled = plans.cancelPlan(PLAYER3, plan.planNo(), "player cancel race");
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");

        // A claim after cancel must not start unit 2 (no PENDING available).
        var late = driver.claim(plan, "w1", PlanTestDriver.token());
        assertThat(late.unit()).isNull();

        List<CraftPlanUnit> units = unitRepo.listByPlan(plan.id());
        assertThat(units.get(0).status()).isEqualTo("DONE");
        assertThat(units.get(1).status()).isEqualTo("SKIPPED");
        assertThat(units.get(2).status()).isEqualTo("SKIPPED");
        // Rewards of the completed unit are NOT rolled back.
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(1);
        // Materials for the two skipped units were never deducted.
        assertThat(inventory.getQty(PLAYER3, "MAT_IRON")).isEqualTo(6);
    }

    @Test
    void cancel_while_a_unit_is_leased_lets_leased_unit_finish_then_plan_cancels() {
        grant(PLAYER3, "MAT_IRON", 6);
        grant(PLAYER3, "MAT_MAGIC_CORE", 4);
        grant(PLAYER3, "MAT_FIRE_SHARD", 2);
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 2);

        // Unit 1 leased and deducted (in flight) when cancel arrives.
        String token = PlanTestDriver.token();
        var claim = driver.claim(plan, "w1", token);
        unitTx.deduct(claim.unit().id(), token);
        plans.cancelPlan(PLAYER3, plan.planNo(), "cancel mid-flight");

        // The leased unit is still settled: rewards issued, status DONE.
        unitTx.reward(claim.unit().id(), token);
        unitTx.writeBackDone(claim.unit().id(), token);
        plans.maybeFinalize(plan.id(), java.time.Instant.now());

        CraftPlan fin = refresh(plan);
        assertThat(fin.status()).isEqualTo("CANCELLED");
        assertThat(fin.completedCount()).isEqualTo(1);
        assertThat(unitRepo.listByPlan(plan.id()).get(1).status()).isEqualTo("SKIPPED");
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ (7)

    @Test
    void last_materials_make_a_partial_plan_remaining_units_skipped_not_failed() {
        // player3 seed: 9 iron / 6 core / 3 shard => exactly 3 swords; plan wants 5.
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 5);

        runOne(plan, "w1");
        runOne(plan, "w1");
        runOne(plan, "w1");
        // 4th claim+execute hits MATERIAL_INSUFFICIENT: unit 4 skipped, plan stops.
        String token = PlanTestDriver.token();
        var claim4 = driver.claim(plan, "w1", token);
        var out4 = driver.execute(plan, claim4, "w1", token);
        assertThat(out4.result()).startsWith("SKIPPED");

        // Unit 5 is then skipped via the stop drain in claim.
        var claim5 = driver.claim(plan, "w1", PlanTestDriver.token());
        assertThat(claim5.unit()).isNull();

        CraftPlan fin = refresh(plan);
        assertThat(fin.status()).isEqualTo("PARTIAL");
        assertThat(fin.completedCount()).isEqualTo(3);
        assertThat(fin.skippedCount()).isEqualTo(2);
        assertThat(fin.failedCount()).isZero();
        assertThat(unitRepo.listByPlan(plan.id())).extracting(CraftPlanUnit::status)
                .containsExactly("DONE", "DONE", "DONE", "SKIPPED", "SKIPPED");
        // Inventory exactly consumed, never negative.
        assertThat(inventory.getQty(PLAYER3, "MAT_IRON")).isZero();
        assertThat(inventory.getQty(PLAYER3, "MAT_MAGIC_CORE")).isZero();
        assertThat(inventory.getQty(PLAYER3, "MAT_FIRE_SHARD")).isZero();
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ misc

    @Test
    void plan_cancel_is_idempotent_and_cancel_of_finished_plan_is_noop() {
        grant(PLAYER3, "MAT_IRON", 3);
        grant(PLAYER3, "MAT_MAGIC_CORE", 2);
        grant(PLAYER3, "MAT_FIRE_SHARD", 1);
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 1);
        runOne(plan, "w1");
        assertThat(refresh(plan).status()).isEqualTo("COMPLETED");
        Map<String, Object> again = plans.cancelPlan(PLAYER3, plan.planNo(), "late");
        assertThat(again.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void every_plan_order_and_ledger_line_carries_planNo_and_unitNo() {
        grant(PLAYER3, "MAT_IRON", 3);
        grant(PLAYER3, "MAT_MAGIC_CORE", 2);
        grant(PLAYER3, "MAT_FIRE_SHARD", 1);
        CraftPlan plan = create(PLAYER3, RECIPE_FIRE_SWORD, 1);
        runOne(plan, "w1");

        CraftPlanUnit u = unitRepo.listByPlan(plan.id()).get(0);
        var lines = ledger.listByPlanNo(plan.planNo());
        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(l -> {
            assertThat(l.planNo()).isEqualTo(plan.planNo());
            assertThat(l.unitNo()).isEqualTo(1);
            assertThat(l.refNo()).isEqualTo(u.orderNo());
        });
    }

    // ---- helpers -----------------------------------------------------------

    private void grant(long player, String item, long qty) {
        inventory.upsertBalance(player, item, qty, java.time.Instant.now());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String catchCode(Runnable r) {
        try {
            r.run();
            return null;
        } catch (ApiException e) {
            return e.getCode();
        }
    }
}
