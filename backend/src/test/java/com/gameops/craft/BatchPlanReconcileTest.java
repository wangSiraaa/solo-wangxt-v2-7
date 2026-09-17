package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.domain.CraftPlanUnit;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.service.PlanReconcileService;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.support.PlanTestDriver;
import com.gameops.craft.support.TestDataResetter;
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
 * Reconciliation detects plan/order/ledger drift; operator repair only appends
 * signed COMPENSATE ledger lines and is safely retryable on one repair key.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.plan.worker.enabled=false",
        "app.plan.lease-seconds=1"
})
class BatchPlanReconcileTest {

    private static final long OPS = 1L;
    private static final long PLAYER3 = 4L;
    private static final long FIRE_SWORD = 1L;

    @Autowired private PlanService plans;
    @Autowired private PlanReconcileService reconcile;
    @Autowired private PlanRepository planRepo;
    @Autowired private PlanUnitRepository unitRepo;
    @Autowired private LedgerRepository ledger;
    @Autowired private InventoryRepository inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestDataResetter resetter;
    @Autowired private PlanTestDriver driver;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    private CraftPlan completedOneUnitPlan() {
        inventory.upsertBalance(PLAYER3, "MAT_IRON", 3, Instant.now());
        inventory.upsertBalance(PLAYER3, "MAT_MAGIC_CORE", 2, Instant.now());
        inventory.upsertBalance(PLAYER3, "MAT_FIRE_SHARD", 1, Instant.now());
        Map<String, Object> body =
                plans.createPlan(PLAYER3, FIRE_SWORD, 1, UUID.randomUUID().toString());
        CraftPlan plan = planRepo.findByNo((String) body.get("planNo")).orElseThrow();
        String token = PlanTestDriver.token();
        var claim = driver.claim(plan, "w1", token);
        driver.execute(plan, claim, "w1", token);
        return planRepo.findByNo(plan.planNo()).orElseThrow();
    }

    @Test
    void healthy_plan_reconciles_with_no_diffs() {
        CraftPlan plan = completedOneUnitPlan();
        Map<String, Object> report = reconcile.reconcilePlan(plan.planNo());
        assertThat(report.get("consistent")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) report.get("diffs");
        assertThat(diffs).isEmpty();
    }

    @Test
    void missing_produce_is_detected_and_repair_appends_one_compensation_retry_safe() {
        CraftPlan plan = completedOneUnitPlan();
        CraftPlanUnit unit = unitRepo.listByPlan(plan.id()).get(0);
        // Simulate a crash-window drift WITHOUT rewriting history: remove the
        // reward effect by deleting the PRODUCE ledger line and subtracting the
        // issued balance back to match, as if the reward never landed (this is
        // how a "deducted but not rewarded" row would reconcile).
        long swordBefore = inventory.getQty(PLAYER3, "EQP_FIRE_SWORD");
        jdbc.update("DELETE FROM ledger_entry WHERE ref_no = ? AND entry_type = 'PRODUCE' AND item_code = 'EQP_FIRE_SWORD'",
                unit.orderNo());
        inventory.upsertBalance(PLAYER3, "EQP_FIRE_SWORD", swordBefore - 1, Instant.now());

        Map<String, Object> report = reconcile.reconcilePlan(plan.planNo());
        assertThat(report.get("consistent")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) report.get("diffs");
        assertThat(diffs).extracting(d -> d.get("type"))
                .contains(PlanReconcileService.TYPE_PRODUCE_MISSING);

        long compsBefore = countCompensates(plan.planNo());

        // First repair: appends exactly one positive COMPENSATE line.
        String repairKey = UUID.randomUUID().toString();
        Map<String, Object> repair1 = reconcile.repair(OPS,
                new PlanReconcileService.RepairRequest(plan.planNo(), unit.unitNo(),
                        PlanReconcileService.TYPE_PRODUCE_MISSING, "EQP_FIRE_SWORD", 1L,
                        "补发丢失的产出"),
                repairKey);
        assertThat(repair1.get("qtyDelta")).isEqualTo(1L);
        assertThat(repair1.get("replayed")).isEqualTo(false);
        String compRefNo = (String) repair1.get("compRefNo");
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(swordBefore);

        // Retrying the SAME repair request posts NO second compensation; replays.
        Map<String, Object> repair2 = reconcile.repair(OPS,
                new PlanReconcileService.RepairRequest(plan.planNo(), unit.unitNo(),
                        PlanReconcileService.TYPE_PRODUCE_MISSING, "EQP_FIRE_SWORD", 1L,
                        "补发丢失的产出"),
                repairKey);
        assertThat(repair2.get("compRefNo")).isEqualTo(compRefNo);
        assertThat(repair2.get("replayed")).isEqualTo(true);
        assertThat(countCompensates(plan.planNo())).isEqualTo(compsBefore + 1);
        // Balance unchanged by the replay (exactly-once).
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(swordBefore);

        // After the append-only compensation the plan reconciles clean.
        Map<String, Object> after = reconcile.reconcilePlan(plan.planNo());
        assertThat(after.get("consistent")).isEqualTo(true);

        // Historical rows were never modified: the original CONSUME/PRODUCE rows
        // are still untouched (PRODUCE stays missing; COMPENSATE is the new trail).
        long produces = ledger.listByRef(unit.orderNo()).stream()
                .filter(l -> "PRODUCE".equals(l.entryType()) && "EQP_FIRE_SWORD".equals(l.itemCode()))
                .count();
        assertThat(produces).isZero();
        long compensates = ledger.listByRef(compRefNo).stream()
                .filter(l -> "COMPENSATE".equals(l.entryType())).count();
        assertThat(compensates).isEqualTo(1);
    }

    @Test
    void negative_compensation_for_missing_consume_is_blocked_when_balance_insufficient() {
        CraftPlan plan = completedOneUnitPlan();
        CraftPlanUnit unit = unitRepo.listByPlan(plan.id()).get(0);
        // Player has no spare gold beyond the 500 seed + 100 produced = 600.
        // Ask to claw back far more than held -> 409, nothing posted.
        long repairsBefore = countCompensates(plan.planNo());
        assertThatThrownBy(() -> reconcile.repair(OPS,
                new PlanReconcileService.RepairRequest(plan.planNo(), unit.unitNo(),
                        PlanReconcileService.TYPE_CONSUME_MISSING, "GOLD", -10_000L, "补扣"),
                UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("COMPENSATE_INSUFFICIENT_BALANCE");
        assertThat(countCompensates(plan.planNo())).isEqualTo(repairsBefore);
    }

    @Test
    void repairs_are_listed_and_repair_key_is_globally_unique() {
        CraftPlan plan = completedOneUnitPlan();
        CraftPlanUnit unit = unitRepo.listByPlan(plan.id()).get(0);
        // Manufacture a one-sword gap (crash-window drift) before repairing.
        long swordBefore = inventory.getQty(PLAYER3, "EQP_FIRE_SWORD");
        jdbc.update("DELETE FROM ledger_entry WHERE ref_no = ? AND entry_type = 'PRODUCE' AND item_code = 'EQP_FIRE_SWORD'",
                unit.orderNo());
        inventory.upsertBalance(PLAYER3, "EQP_FIRE_SWORD", swordBefore - 1, Instant.now());

        String key = UUID.randomUUID().toString();
        reconcile.repair(OPS, new PlanReconcileService.RepairRequest(
                plan.planNo(), 1, PlanReconcileService.TYPE_PRODUCE_MISSING,
                "EQP_FIRE_SWORD", 1L, null), key);
        assertThat(reconcile.reconcilePlan(plan.planNo()).get("consistent")).isEqualTo(true);

        // Same key reused for a DIFFERENT request still replays (one key=one outcome).
        Map<String, Object> again = reconcile.repair(OPS,
                new PlanReconcileService.RepairRequest(plan.planNo(), 1,
                        PlanReconcileService.TYPE_PRODUCE_MISSING,
                        "EQP_FIRE_SWORD", 999L, "different"),
                key);
        assertThat(((Number) again.get("qtyDelta")).longValue()).isEqualTo(1L); // original, not 999

        List<Map<String, Object>> rows = reconcile.listRepairs(plan.planNo(), 50);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("repairKey")).isEqualTo(key);
    }

    private long countCompensates(String planNo) {
        return ledger.listByPlanNo(planNo).stream()
                .filter(l -> "COMPENSATE".equals(l.entryType())).count();
    }
}
