package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.domain.BatchPlan;
import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.repo.RepairRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanTxService;
import com.gameops.craft.service.PlanWorker;
import com.gameops.craft.service.ReconcileService;
import com.gameops.craft.support.TestDataResetter;
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
 * Reconciliation finds status/inventory/ledger drift, and operator repair completes the
 * missing side with an APPEND-ONLY compensation. The same repair request retried any number
 * of times produces exactly one compensation and never rewrites history.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.sweeper.delay-ms=600000",
        "app.plan-worker.delay-ms=600000"
})
class BatchPlanReconcileTest {

    @Autowired private PlanService planService;
    @Autowired private PlanWorker worker;
    @Autowired private ReconcileService reconcile;
    @Autowired private BatchPlanRepository plans;
    @Autowired private PlanUnitRepository units;
    @Autowired private LedgerRepository ledger;
    @Autowired private RepairRepository repairs;
    @Autowired private InventoryRepository inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestDataResetter resetter;

    private static final long PLAYER = 4L;
    private static final int RECIPE = 1;
    private static final long OPERATOR = 1L;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    private PlanUnit runOneDoneUnit() {
        var created = planService.createPlan(PLAYER, RECIPE, 1, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");
        worker.runOnce();
        var plan = plans.findByNo(planNo).orElseThrow();
        return units.listByPlan(plan.id()).get(0);
    }

    @Test
    void healthy_plan_reconciles_clean() {
        var created = planService.createPlan(PLAYER, RECIPE, 2, UUID.randomUUID().toString());
        worker.runOnce();
        Map<String, Object> r = reconcile.reconcilePlan((String) created.get("planNo"));
        assertThat(r.get("consistent")).isEqualTo(true);
        assertThat(((List<?>) r.get("issues"))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missing_produce_is_detected_and_repaired_once_across_retries() {
        PlanUnit done = runOneDoneUnit();
        String planNo = done.planNo();
        long swordsBefore = inventory.getQty(PLAYER, "EQP_FIRE_SWORD");
        long goldBefore = inventory.getQty(PLAYER, "GOLD");

        // Simulate the "deducted, but one output lost" inconsistency: delete the SWORD produce row
        // AND reverse its inventory effect (balance only). Historical rows are never edited by
        // the product; this direct SQL is the fault injection for the test.
        jdbc.update("DELETE FROM ledger_entry WHERE ref_no = ? AND item_code = 'EQP_FIRE_SWORD' AND entry_type = 'PRODUCE'",
                done.orderNo());
        jdbc.update("UPDATE player_inventory SET qty = qty - 1, updated_at = CURRENT_TIMESTAMP(3)"
                + " WHERE player_id = ? AND item_code = 'EQP_FIRE_SWORD'", PLAYER);
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(swordsBefore - 1);

        Map<String, Object> report = reconcile.reconcilePlan(planNo);
        assertThat(report.get("consistent")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<ReconcileService.Issue> issues = (List<ReconcileService.Issue>) report.get("issues");
        assertThat(issues).anyMatch(i -> "MISSING_PRODUCE".equals(i.issueType())
                && "EQP_FIRE_SWORD".equals(i.itemCode()));

        String repairKey = UUID.randomUUID().toString();
        var req = new ReconcileService.RepairRequest(
                repairKey, planNo, done.unitNo(), "MISSING_PRODUCE", "EQP_FIRE_SWORD");

        ReconcileService.RepairResult first = reconcile.repair(req, OPERATOR);
        assertThat(first.result()).isEqualTo("APPLIED");
        assertThat(first.replayed()).isFalse();

        // Balance + the missing side restored exactly once.
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(swordsBefore);
        long produceAfter = ledger.listByRef(done.orderNo()).stream()
                .filter(e -> "PRODUCE".equals(e.entryType()) && "EQP_FIRE_SWORD".equals(e.itemCode()))
                .count();
        assertThat(produceAfter).isEqualTo(1);
        // An append-only COMPENSATE audit row exists, and gold side was untouched.
        long compensates = ledger.listByPlan(planNo).stream()
                .filter(e -> "COMPENSATE".equals(e.entryType())).count();
        assertThat(compensates).isEqualTo(1);
        assertThat(inventory.getQty(PLAYER, "GOLD")).isEqualTo(goldBefore);

        // Retry the SAME repair request several times: replay, no second compensation.
        for (int i = 0; i < 3; i++) {
            ReconcileService.RepairResult retry = reconcile.repair(req, OPERATOR);
            assertThat(retry.replayed()).isTrue();
            assertThat(retry.repairNo()).isEqualTo(first.repairNo());
        }
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(swordsBefore);
        assertThat(ledger.listByPlan(planNo).stream()
                .filter(e -> "COMPENSATE".equals(e.entryType())).count())
                .as("no second compensation on retry").isEqualTo(1);

        // Reconcile is now clean for that issue.
        Map<String, Object> after = reconcile.reconcilePlan(planNo);
        @SuppressWarnings("unchecked")
        List<ReconcileService.Issue> afterIssues =
                (List<ReconcileService.Issue>) after.get("issues");
        assertThat(afterIssues).noneMatch(i -> "MISSING_PRODUCE".equals(i.issueType()));

        // Repair history is recorded once.
        assertThat(repairs.listByPlan(planNo)).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrent_identical_repairs_create_only_one_compensation() throws Exception {
        PlanUnit done = runOneDoneUnit();
        String planNo = done.planNo();
        jdbc.update("DELETE FROM ledger_entry WHERE ref_no = ? AND item_code = 'EQP_FIRE_SWORD' AND entry_type = 'PRODUCE'",
                done.orderNo());
        jdbc.update("UPDATE player_inventory SET qty = qty - 1, updated_at = CURRENT_TIMESTAMP(3)"
                + " WHERE player_id = ? AND item_code = 'EQP_FIRE_SWORD'", PLAYER);

        String repairKey = UUID.randomUUID().toString();
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(threads);
        var applied = new java.util.concurrent.atomic.AtomicInteger();
        var replayed = new java.util.concurrent.atomic.AtomicInteger();
        var errs = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var r = reconcile.repair(new ReconcileService.RepairRequest(
                            repairKey, planNo, done.unitNo(), "MISSING_PRODUCE", "EQP_FIRE_SWORD"), OPERATOR);
                    if (r.replayed()) replayed.incrementAndGet(); else applied.incrementAndGet();
                } catch (Exception e) {
                    errs.add(e);
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertThat(finished.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        errs.forEach(Throwable::printStackTrace);
        assertThat(errs).isEmpty();

        assertThat(applied.get()).isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(threads - 1);
        assertThat(ledger.listByPlan(planNo).stream()
                .filter(e -> "COMPENSATE".equals(e.entryType())).count()).isEqualTo(1);
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void repair_never_modifies_historical_rows_but_appends_compensation() {
        PlanUnit done = runOneDoneUnit();
        long goldProduceId = ledger.listByRef(done.orderNo()).stream()
                .filter(e -> "GOLD".equals(e.itemCode()) && "PRODUCE".equals(e.entryType()))
                .findFirst().orElseThrow().id();

        jdbc.update("DELETE FROM ledger_entry WHERE ref_no = ? AND item_code = 'GOLD' AND entry_type = 'PRODUCE'",
                done.orderNo());
        jdbc.update("UPDATE player_inventory SET qty = qty - 100, updated_at = CURRENT_TIMESTAMP(3)"
                + " WHERE player_id = ? AND item_code = 'GOLD'", PLAYER);

        reconcile.repair(new ReconcileService.RepairRequest(
                UUID.randomUUID().toString(), done.planNo(), done.unitNo(),
                "MISSING_PRODUCE", "GOLD"), OPERATOR);

        // The repaired PRODUCE is a NEW row (new id); the original row id was not resurrected/edited.
        long newGoldId = ledger.listByRef(done.orderNo()).stream()
                .filter(e -> "GOLD".equals(e.itemCode()) && "PRODUCE".equals(e.entryType()))
                .findFirst().orElseThrow().id();
        assertThat(newGoldId).isNotEqualTo(goldProduceId);
        // Original consume rows remain intact.
        long consumes = ledger.listByRef(done.orderNo()).stream()
                .filter(e -> "CONSUME".equals(e.entryType())).count();
        assertThat(consumes).isEqualTo(3);
    }
}
