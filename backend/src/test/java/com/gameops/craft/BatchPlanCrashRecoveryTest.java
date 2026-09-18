package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanTxService;
import com.gameops.craft.service.PlanWorker;
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
 * Crash windows for a leased unit. After the lease expires ANOTHER worker resumes purely from
 * persisted state and never changes the inventory twice:
 *   window A: Tx1 committed (DEDUCTED + CONSUME), crash before Tx2 -> reward issued once
 *   window B: Tx2 committed (PRODUCE), crash before Tx3 -> only DONE is rewritten, no 2nd reward
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.sweeper.delay-ms=600000",
        "app.plan-worker.delay-ms=600000"
})
class BatchPlanCrashRecoveryTest {

    @Autowired private PlanService planService;
    @Autowired private PlanTxService tx;
    @Autowired private PlanWorker worker;
    @Autowired private BatchPlanRepository plans;
    @Autowired private PlanUnitRepository units;
    @Autowired private LedgerRepository ledger;
    @Autowired private InventoryRepository inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestDataResetter resetter;

    private static final long PLAYER = 4L;
    private static final int RECIPE = 1;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    private PlanUnit setupOneUnitPlan() {
        var created = planService.createPlan(PLAYER, RECIPE, 1, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");
        var plan = plans.findByNo(planNo).orElseThrow();
        return units.listByPlan(plan.id()).get(0);
    }

    private void claimAndDeduct(PlanUnit target, String owner) {
        Boolean acquired = tx.acquireUnit(target, owner);
        assertThat(acquired).isTrue();
        PlanUnit claimed = units.findByIdForUpdate(target.id()).orElseThrow();
        tx.deductMaterials(claimed); // Tx1: durable consume, status DEDUCTED
    }

    private void expireLease(long unitId) {
        jdbc.update("UPDATE plan_unit SET lease_expires_at = TIMESTAMP '2000-01-01 00:00:00' WHERE id = ?",
                unitId);
    }

    @Test
    void crash_after_deduct_before_reward_lease_recovery_issues_reward_once() {
        long ironBefore = inventory.getQty(PLAYER, "MAT_IRON");
        PlanUnit target = setupOneUnitPlan();
        claimAndDeduct(target, "dead-worker");

        // ---- crash window A: materials consumed, reward NOT issued, lease now expired ----
        PlanUnit deducted = units.findByIdForUpdate(target.id()).orElseThrow();
        assertThat(deducted.status()).isEqualTo("DEDUCTED");
        long consumeCount = ledger.listByRef(deducted.orderNo()).stream()
                .filter(e -> "CONSUME".equals(e.entryType())).count();
        assertThat(consumeCount).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isZero();
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(ironBefore - 3);

        expireLease(target.id());

        // A DIFFERENT worker recovers; run twice to prove resumption is idempotent too.
        worker.runOnce();
        expireLease(target.id());
        worker.runOnce();

        PlanUnit done = units.findByIdForUpdate(target.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("DONE");

        // Reward exactly once, materials not deducted twice.
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(1);
        assertThat(inventory.getQty(PLAYER, "GOLD")).isEqualTo(500 + 100);
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(ironBefore - 3);

        long produce = ledger.listByRef(done.orderNo()).stream()
                .filter(e -> "PRODUCE".equals(e.entryType())).count();
        assertThat(produce).isEqualTo(2); // sword + gold

        var detail = planService.planDetail(PLAYER, done.planNo());
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void crash_after_reward_before_writeback_lease_recovery_only_rewrites_status() {
        PlanUnit target = setupOneUnitPlan();
        claimAndDeduct(target, "dead-worker");
        PlanUnit deducted = units.findByIdForUpdate(target.id()).orElseThrow();

        // Tx2 committed: reward issued + order COMMITTED...
        Boolean issued = tx.issueRewards(deducted);
        assertThat(issued).isTrue();
        // ...but the process dies BEFORE Tx3 wrote DONE. Unit row is still DEDUCTED.
        PlanUnit stuck = units.findByIdForUpdate(target.id()).orElseThrow();
        assertThat(stuck.status()).isEqualTo("DEDUCTED");
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(1);

        expireLease(target.id());

        // Recovery worker must NOT credit a second sword; it only rewrites DONE + finalizes.
        worker.runOnce();
        expireLease(target.id());
        worker.runOnce();

        PlanUnit done = units.findByIdForUpdate(target.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).as("no double reward").isEqualTo(1);
        assertThat(inventory.getQty(PLAYER, "GOLD")).isEqualTo(600);

        long produce = ledger.listByRef(done.orderNo()).stream()
                .filter(e -> "PRODUCE".equals(e.entryType())).count();
        assertThat(produce).isEqualTo(2);

        var detail = planService.planDetail(PLAYER, done.planNo());
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void crash_before_deduct_commits_restarts_the_unit_without_double_changes() {
        // Claim succeeds (RUNNING + order row) but Tx1 never commits -> restart after expiry.
        PlanUnit target = setupOneUnitPlan();
        Boolean acquired = tx.acquireUnit(target, "dead-worker");
        assertThat(acquired).isTrue();
        PlanUnit claimed = units.findByIdForUpdate(target.id()).orElseThrow();
        assertThat(claimed.status()).isEqualTo("RUNNING");
        // No ledger movement at all.
        assertThat(ledger.listByRef(claimed.orderNo())).isEmpty();

        expireLease(target.id());
        worker.runOnce();

        PlanUnit done = units.findByIdForUpdate(target.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(inventory.getQty(PLAYER, "EQP_FIRE_SWORD")).isEqualTo(1);
        assertThat(inventory.getQty(PLAYER, "MAT_IRON")).isEqualTo(9 - 3);
    }
}
