package com.gameops.craft.service;

import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.repo.PlanUnitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background worker driving plan units. Multiple instances/processes may run concurrently:
 * claiming is a row-lock + CAS lease, transitions are CAS, and both ledger mutations carry
 * unique keys — so two workers grabbing the same unit_no can change the inventory at most once.
 *
 * Per unit the worker runs the three checkpoints in separate transactions (see PlanTxService).
 * A lease (default 30s) bounds crash recovery: if the process dies in any window, the unit is
 * reacquired after lease expiry and resumed purely from the persisted status/ledger rows.
 */
@Component
public class PlanWorker {

    private static final Logger log = LoggerFactory.getLogger(PlanWorker.class);

    private final PlanTxService tx;
    private final PlanUnitRepository units;
    private final TransactionTemplate txTemplate;
    private final int batchSize;
    private final String workerId;

    public PlanWorker(PlanTxService tx, PlanUnitRepository units, TransactionTemplate txTemplate,
                      @Value("${app.plan-worker.batch-size:16}") int batchSize) {
        this.tx = tx;
        this.units = units;
        this.txTemplate = txTemplate;
        this.batchSize = batchSize;
        this.workerId = "worker-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public String workerId() {
        return workerId;
    }

    @Scheduled(fixedDelayString = "${app.plan-worker.delay-ms:1000}")
    public void tick() {
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("plan worker tick failed: {}", e.getMessage());
        }
    }

    /** One polling round. Also invoked directly by tests (deterministic, no scheduling wait). */
    public void runOnce() {
        java.util.List<PlanUnit> candidates;
        try {
            candidates = txTemplate.execute(s -> tx.lockClaimCandidates(batchSize));
        } catch (Exception e) {
            log.warn("claim query failed: {}", e.getMessage());
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (PlanUnit candidate : candidates) {
            try {
                processCandidate(candidate);
            } catch (Exception e) {
                // Next tick / lease expiry retries; all CAS + unique keys make that safe.
                log.warn("unit {} processing failed: {}", candidate.id(), e.toString());
            }
        }
    }

    /**
     * Process one already-identified candidate directly (test support + targeted recovery).
     * Production polling goes through {@link #runOnce()}; this lets tests drive a specific unit
     * (e.g. after simulating a crash with an expired lease held by a different owner).
     */
    public void processOne(com.gameops.craft.domain.PlanUnit candidate) {
        processCandidate(candidate);
    }

    /** Runs the three checkpoints for one claimed/recovered unit, each in its own transaction. */
    void processCandidate(PlanUnit candidate) {
        String owner = workerId;

        // ---- acquire (own tx): CAS claim of PENDING, or reacquire an expired lease ----
        Boolean acquired = txTemplate.execute(s -> tx.acquireUnit(candidate, owner));
        if (!Boolean.TRUE.equals(acquired)) {
            return; // lost the claim race, or the plan stop gate (cancel/activity end) flipped first
        }
        PlanUnit mine = txTemplate.execute(s -> units.findByIdForUpdate(candidate.id()).orElseThrow());
        if (mine == null || "DONE".equals(mine.status()) || "SKIPPED".equals(mine.status())
                || "FAILED".equals(mine.status())) {
            return;
        }

        // ---- decide the resume point purely from durable state (covers every crash window) ----
        PlanTxService.RecoveryAction action = tx.classifyRecovery(mine);

        // ---- Tx1: deduct materials (skipped when Tx1 had already durably committed) ----
        if (action == PlanTxService.RecoveryAction.RESTART_DEDUCT) {
            try {
                txTemplate.executeWithoutResult(s -> tx.deductMaterials(mine));
            } catch (PlanTxService.ShortageSignal e) {
                // Deduction tx rolled back: freeze plan + skip this never-started unit, then stop.
                txTemplate.executeWithoutResult(s -> tx.handleShortage(e.unitId, e.planId, e.getMessage()));
                return;
            }
            action = PlanTxService.RecoveryAction.ISSUE_REWARD;
        }

        // ---- Tx2: issue rewards (idempotent: deducted-but-not-issued window) ----
        if (action != PlanTxService.RecoveryAction.ALL_DONE_WRITEBACK) {
            txTemplate.execute(s -> tx.issueRewards(mine));
        }

        // ---- Tx3: write DONE + finalize plan (issued-but-not-written-back window) ----
        txTemplate.executeWithoutResult(s -> tx.writeBackDone(mine));
    }
}
