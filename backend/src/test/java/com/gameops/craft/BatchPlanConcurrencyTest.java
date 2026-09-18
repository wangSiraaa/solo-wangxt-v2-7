package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanTxService;
import com.gameops.craft.service.PlanWorker;
import com.gameops.craft.support.TestDataResetter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.sweeper.delay-ms=600000",
        "app.plan-worker.delay-ms=600000"
})
class BatchPlanConcurrencyTest {

    @Autowired private PlanService planService;
    @Autowired private PlanTxService tx;
    @Autowired private PlanWorker worker;
    @Autowired private BatchPlanRepository plans;
    @Autowired private PlanUnitRepository units;
    @Autowired private InventoryRepository inventory;
    @Autowired private TestDataResetter resetter;

    private static final long PLAYER = 4L;
    private static final int RECIPE = 1;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    @Test
    void thirty_two_concurrent_creates_with_same_key_yield_exactly_one_plan() throws Exception {
        String key = UUID.randomUUID().toString();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> planNos = new ConcurrentLinkedQueue<>();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        var body = planService.createPlan(PLAYER, RECIPE, 10, key);
                        planNos.add((String) body.get("planNo"));
                    } catch (ApiException e) {
                        if ("RETRY_IN_FLIGHT".equals(e.getCode()) || "IDEMPOTENCY_RACE".equals(e.getCode())) {
                            rejected.incrementAndGet();
                        } else {
                            errors.incrementAndGet();
                        }
                    }
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

        // After the dust settles, sequential retries of the SAME key all replay the one plan.
        for (int i = 0; i < 4; i++) {
            planNos.add((String) planService.createPlan(PLAYER, RECIPE, 10, key).get("planNo"));
        }

        long distinct = planNos.stream().distinct().count();
        assertThat(distinct).as("only one plan ever created for the key").isEqualTo(1);
        assertThat(errors.get()).isZero();
        assertThat(planNos.size() + rejected.get()).isEqualTo(threads + 4);

        String planNo = planNos.iterator().next();
        var plan = plans.findByNo(planNo).orElseThrow();
        assertThat(plan.totalUnits()).isEqualTo(10);
        assertThat(units.listByPlan(plan.id())).hasSize(10);

        // Nothing was executed yet; no materials consumed by creation.
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(9);
    }

    @Test
    void two_workers_grab_the_same_unit_but_it_deducts_and_issues_exactly_once() throws Exception {
        var created = planService.createPlan(PLAYER, RECIPE, 3, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");
        var plan = plans.findByNo(planNo).orElseThrow();
        PlanUnit target = units.listByPlan(plan.id()).get(0);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Boolean ok = tx.acquireUnit(target, "racer-A-or-B");
                    if (Boolean.TRUE.equals(ok)) {
                        acquired.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(acquired.get()).as("exactly one worker wins the lease CAS").isEqualTo(1);

        // Simulate the winning worker dying after claiming: the lease must expire before another
        // worker takes over. Force the lease into the past, then process units with THIS worker.
        expireAllLeases(plan.id());
        drainWorker();

        var detail = planService.planDetail(PLAYER, planNo);
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
        assertThat(detail.get("completedCount")).isEqualTo(3);
        // The contested unit changed the inventory exactly once.
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER, "GOLD")).isEqualTo(800);
    }

    @Test
    void parallel_workers_process_all_units_with_single_inventory_effect() throws Exception {
        // 3-unit plan (player4 can afford exactly 3) executed by two threads calling runOnce.
        var created = planService.createPlan(PLAYER, RECIPE, 3, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futs.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < 5; r++) {
                        worker.runOnce();
                    }
                } catch (Exception ignored) {
                }
            }));
        }
        start.countDown();
        for (var f : futs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        var detail = planService.planDetail(PLAYER, planNo);
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
        assertThat(detail.get("completedCount")).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER, "GOLD")).isEqualTo(800);
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isZero();
    }

    /** Repeatedly run worker rounds until the plan settles (used to keep tests deterministic). */
    private void drainWorker() {
        for (int i = 0; i < 10; i++) {
            worker.runOnce();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void expireAllLeases(long planId) {
        jdbc.update("UPDATE plan_unit SET lease_expires_at = TIMESTAMP '2000-01-01 00:00:00' WHERE plan_id = ?",
                planId);
    }
}
