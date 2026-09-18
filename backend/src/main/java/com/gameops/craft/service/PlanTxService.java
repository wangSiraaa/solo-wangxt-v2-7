package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.BatchPlan;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.domain.RecipeVersion;
import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.HoldRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.repo.RecipeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Transactional core of batch synthesis plans.
 *
 * A unit moves through THREE durable checkpoints in SEPARATE transactions so that a process
 * crash in either window is recoverable purely from persisted state (never from memory):
 *
 *   Tx1 deductMaterials : PENDING->RUNNING(lease) ; create craft_order + holds(settled)
 *                         + CONSUME ledger ; RUNNING->DEDUCTED        ("已扣料")
 *   Tx2 issueRewards    : read snapshot, credit outputs + PRODUCE ledger (unique key)
 *                         + craft_order PREOCCUPIED->COMMITTED        ("已发奖")
 *   Tx3 writeBackDone   : DEDUCTED->DONE, release lease; finalize plan ("回写状态")
 *
 * Crash recovery after lease expiry:
 *   - no CONSUME rows     -> unit restarts at Tx1 (Tx1 rollback discarded everything)
 *   - CONSUME, no PRODUCE -> Tx2 issues the reward once (unique (ref,player,item,type))
 *   - PRODUCE present     -> skip reward, only rewrite DONE in Tx3
 */
@Service
public class PlanTxService {

    private static final Logger log = LoggerFactory.getLogger(PlanTxService.class);

    private final BatchPlanRepository plans;
    private final PlanUnitRepository units;
    private final RecipeRepository recipes;
    private final OrderRepository orders;
    private final InventoryRepository inventory;
    private final HoldRepository holds;
    private final LedgerRepository ledger;
    private final Clock clock;

    public PlanTxService(BatchPlanRepository plans, PlanUnitRepository units, RecipeRepository recipes,
                         OrderRepository orders, InventoryRepository inventory, HoldRepository holds,
                         LedgerRepository ledger, Clock clock) {
        this.plans = plans;
        this.units = units;
        this.recipes = recipes;
        this.orders = orders;
        this.inventory = inventory;
        this.holds = holds;
        this.ledger = ledger;
        this.clock = clock;
    }

    public static final int MAX_UNITS = 100;
    public static final int MIN_UNITS = 1;

    // ---- plan creation -----------------------------------------------------

    public record CreatedPlan(BatchPlan plan, RecipeVersion version) {}

    /**
     * Create the plan + all unit rows in ONE transaction and freeze the recipe snapshot.
     * No materials are deducted here; the idempotency unique-key (facade) plus this insert
     * guarantee concurrent same-key creates yield exactly one plan.
     */
    @Transactional
    public CreatedPlan createPlan(String planNo, long playerId, long recipeId, int totalUnits, Instant now) {
        if (totalUnits < MIN_UNITS || totalUnits > MAX_UNITS) {
            throw ApiException.badRequest("PLAN_SIZE_INVALID",
                    "批量次数必须在 " + MIN_UNITS + "–" + MAX_UNITS + " 之间");
        }
        RecipeRepository.RecipeHeader header = recipes.lockHeader(recipeId);
        if (header == null) {
            throw ApiException.notFound("RECIPE_NOT_FOUND", "配方不存在");
        }
        if (!"ACTIVE".equals(header.status())) {
            throw ApiException.conflict("RECIPE_CLOSED", "配方已下架，无法创建批量计划");
        }
        RecipeVersion version = recipes.findPublishedVersionForUpdate(recipeId)
                .orElseThrow(() -> ApiException.conflict("RECIPE_NOT_PUBLISHED", "配方无已发布版本"));
        if (!version.isOpenAt(now)) {
            throw ApiException.conflict("ACTIVITY_NOT_OPEN",
                    "活动未在有效期内（开始 " + version.startTime() + "，结束 " + version.endTime() + "）");
        }
        List<ItemQty> inputs = sortedByItem(version.inputs());
        List<ItemQty> outputs = version.outputs();
        if (inputs.isEmpty() || outputs.isEmpty() || version.craftTimeoutSeconds() <= 0) {
            throw ApiException.badRequest("RECIPE_INCOMPLETE", "发布版本缺少材料/产出/超时配置");
        }
        validatePositive(inputs);
        validatePositive(outputs);

        String inputsJson = writeJson(inputs);
        String outputsJson = writeJson(outputs);
        long planId = plans.insert(planNo, playerId, recipeId, version.id(), totalUnits,
                inputsJson, outputsJson, now);
        for (int n = 1; n <= totalUnits; n++) {
            units.insert(planId, planNo, n, playerId, recipeId, version.id(), now);
        }
        BatchPlan saved = plans.findByIdForUpdate(planId).orElseThrow();
        return new CreatedPlan(saved, version);
    }

    // ---- worker: unit execution (3 durable checkpoints) --------------------

    /**
     * Candidate rows the worker claims: fresh PENDING units of plans accepting new work,
     * plus expired leases needing recovery. Locked in the worker's claim transaction.
     */
    /**
     * Candidate PROBE for a worker round (non-locking reads). It only returns rows worth
     * attempting; all locking and authoritative decisions happen later in acquireUnit with a
     * consistent plan-first/unit-second lock order.
     */
    @Transactional(readOnly = true)
    public List<PlanUnit> lockClaimCandidates(int batch) {
        List<PlanUnit> fresh = units.listPendingCandidates(batch);
        List<PlanUnit> recovered = units.listExpiredLeases(Instant.now(clock), batch);
        Map<Long, PlanUnit> dedup = new LinkedHashMap<>();
        fresh.forEach(u -> dedup.put(u.id(), u));
        recovered.forEach(u -> dedup.putIfAbsent(u.id(), u));
        return List.copyOf(dedup.values());
    }

    /**
     * Lease decision for one locked candidate. Returns true if THIS worker now owns it.
     * Lock order is ALWAYS plan-header first, then unit row (same order as cancel/finalize),
     * so concurrent workers and a cancel cannot deadlock.
     * PENDING  -> CAS claim (creates the order row; no balance change yet).
     * expired  -> reacquire for crash recovery.
     */
    @Transactional
    public boolean acquireUnit(PlanUnit candidate, String owner) {
        Instant now = Instant.now(clock);
        BatchPlan plan = plans.findByIdForUpdate(candidate.planId()).orElseThrow();
        PlanUnit u = units.findByIdForUpdate(candidate.id()).orElseThrow();
        return switch (u.status()) {
            case "PENDING" -> {
                // Re-check the plan gate under lock: cancel/activity-end/shortage may have flipped it.
                if (plan.stopNewUnits() || plan.isTerminal()) {
                    yield false;
                }
                RecipeVersion v = recipes.findVersionById(u.recipeVersionId()).orElseThrow();
                // Activity-end stop gate applies only to STARTING a new sequence number.
                if (!v.isOpenAt(now)) {
                    plans.markStopNewUnits(plan.id(), "activity ended: no new units start", now);
                    yield false;
                }
                String orderNo = newUnitOrderNo(clock);
                // Far-future deadline: the unit obeys its plan lease, never the single-craft timeout.
                Instant deadline = now.plusSeconds(365 * 24 * 3600L);
                orders.insert(orderNo, u.playerId(), u.recipeId(), u.recipeVersionId(),
                        deadline, now, u.planNo(), u.unitNo());
                int cas = units.casClaim(u, owner, orderNo, now, leaseExpiry(now));
                plans.casRunning(plan.id(), now);
                yield cas == 1;
            }
            case "RUNNING", "DEDUCTED" -> units.reacquire(u, owner, now, leaseExpiry(now)) == 1;
            default -> false; // DONE/FAILED/SKIPPED
        };
    }

    /**
     * Tx1 checkpoint. Consume one unit's materials atomically and persist DEDUCTED.
     * Insufficient stock freezes the plan (no new sequence numbers) and skips THIS un-started
     * unit; already deducted units in flight still finish.
     */
    @Transactional
    public void deductMaterials(PlanUnit claimed) {
        Instant now = Instant.now(clock);
        // Lock order: plan header -> unit row -> inventory rows (matches cancel/finalize).
        BatchPlan plan = plans.findByIdForUpdate(claimed.planId()).orElseThrow();
        PlanUnit u = units.findByIdForUpdate(claimed.id()).orElseThrow();
        if (!"RUNNING".equals(u.status())) {
            return; // another worker/recovery already advanced it
        }
        List<ItemQty> inputs = parseItems(plan.inputsJson());

        // Deduct row-by-row, sorted (same lock order as single craft). On shortage: roll back,
        // freeze the plan and skip this never-deducted unit.
        for (ItemQty in : inputs) {
            int changed = inventory.deductIfEnough(u.playerId(), in.getItemCode(), in.getQty(), now);
            if (changed == 0) {
                // Roll back partial deductions of this tx; the worker then freezes + skips.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw new ShortageSignal(u.id(), u.planId(),
                        "材料不足：" + in.getItemCode() + " 需要 " + in.getQty());
            }
            long orderId = orders.findByNo(u.orderNo()).orElseThrow().id();
            holds.createAndSettle(orderId, u.playerId(), in.getItemCode(), in.getQty(), now);
            ledger.insert(u.orderNo(), u.playerId(), in.getItemCode(), "CONSUME", -in.getQty(),
                    null, "POSTED",
                    "批量计划消耗 planNo=" + u.planNo() + " unitNo=" + u.unitNo(),
                    now, u.planNo(), u.unitNo());
        }
        int cas = units.casDeducted(u.id(), claimed.leaseOwner(), now);
        if (cas == 0) {
            throw new IllegalStateException("DEDUCTED CAS lost for unit " + u.id());
        }
    }

    /**
     * Convert a ShortageSignal (raised inside the deduction tx) into the freeze+skip action.
     * Must run AFTER the shortage transaction rolled back.
     */
    @Transactional
    public void handleShortage(long unitId, long planId, String reason) {
        Instant now = Instant.now(clock);
        plans.markStopNewUnits(planId, reason, now);
        units.findByIdForUpdate(unitId).ifPresent(u -> {
            if ("RUNNING".equals(u.status())) {
                units.forceSkipRunning(unitId, reason);
                if (u.orderNo() != null) {
                    orders.casCancelBatchUnitByNo(u.orderNo(), reason, now);
                }
            }
        });
        // Same-batch siblings that were claimed but never deducted stop immediately too.
        for (long[] row : units.findRunningUndeductedUnits(planId)) {
            long siblingId = row[0];
            long consumed = row[1];
            if (consumed == 0) {
                PlanUnit sibling = units.findByIdForUpdate(siblingId).orElse(null);
                if (sibling != null && "RUNNING".equals(sibling.status())) {
                    units.forceSkipRunning(siblingId, reason);
                    if (sibling.orderNo() != null) {
                        orders.casCancelBatchUnitByNo(sibling.orderNo(), reason, now);
                    }
                }
            }
        }
        tryFinalize(planId);
    }

    /**
     * Tx2 checkpoint. Issue outputs against the plan snapshot exactly once.
     * Idempotent on recovery: if PRODUCE rows already exist (crash after Tx2, before Tx3),
     * nothing is credited a second time.
     */
    @Transactional
    public boolean issueRewards(PlanUnit claimed) {
        Instant now = Instant.now(clock);
        BatchPlan plan = plans.findByIdForUpdate(claimed.planId()).orElseThrow();
        PlanUnit u = units.findByIdForUpdate(claimed.id()).orElseThrow();
        if (!"RUNNING".equals(u.status()) && !"DEDUCTED".equals(u.status())) {
            return false;
        }
        List<ItemQty> outputs = parseItems(plan.outputsJson());
        boolean allAlreadyIssued = true;
        boolean anyMissing = false;
        for (ItemQty out : outputs) {
            if (ledger.exists(u.orderNo(), u.playerId(), out.getItemCode(), "PRODUCE")) {
                continue;
            }
            allAlreadyIssued = false;
            anyMissing = true;
        }
        if (!anyMissing) {
            // Tx2-crash recovery window: reward was issued, only the DONE writeback was lost.
            orders.findByNo(u.orderNo()).ifPresent(o -> {
                if ("PREOCCUPIED".equals(o.status())) {
                    orders.casCommitBatchUnit(o.id(), now);
                }
            });
            log.info("unit {} rewards already present; skipping duplicate issue", u.id());
            return allAlreadyIssued;
        }
        // Credit each missing output. The ledger unique key is the hard anti-double-issue anchor.
        for (ItemQty out : outputs) {
            if (ledger.exists(u.orderNo(), u.playerId(), out.getItemCode(), "PRODUCE")) {
                continue;
            }
            inventory.credit(u.playerId(), out.getItemCode(), out.getQty(), now);
            ledger.insert(u.orderNo(), u.playerId(), out.getItemCode(), "PRODUCE", out.getQty(),
                    null, "POSTED",
                    "批量计划产出 planNo=" + u.planNo() + " unitNo=" + u.unitNo(),
                    now, u.planNo(), u.unitNo());
        }
        long orderId = orders.findByNo(u.orderNo()).orElseThrow().id();
        orders.casCommitBatchUnit(orderId, now);
        return true;
    }

    /**
     * Tx3 checkpoint. Persist DONE, drop the lease, and (under plan lock) recompute counts
     * and decide the terminal plan status.
     */
    @Transactional
    public void writeBackDone(PlanUnit claimed) {
        Instant now = Instant.now(clock);
        plans.findByIdForUpdate(claimed.planId()).orElseThrow(); // plan-first lock order
        PlanUnit u = units.findByIdForUpdate(claimed.id()).orElseThrow();
        int cas;
        if (u.leaseOwner() != null && u.leaseOwner().equals(claimed.leaseOwner())) {
            cas = units.casDone(u.id(), claimed.leaseOwner(), now);
        } else {
            cas = units.casDoneWithoutLease(u.id(), now);
        }
        if (cas == 0) {
            PlanUnit again = units.findByIdForUpdate(u.id()).orElseThrow();
            if (!"DONE".equals(again.status())) {
                throw new IllegalStateException("DONE CAS lost for unit " + u.id());
            }
        }
        tryFinalize(u.planId());
    }

    /**
     * Finalize the plan when no unit can still move. Lock the plan row, count unit states.
     * Only PENDING/RUNNING plans can terminate, and the transition is a CAS.
     */
    @Transactional
    public boolean tryFinalize(long planId) {
        Instant now = Instant.now(clock);
        BatchPlan plan = plans.findByIdForUpdate(planId).orElseThrow();
        if (plan.isTerminal()) {
            return true;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlanUnitRepository.StatusCount sc : units.countByStatus(planId)) {
            counts.put(sc.status(), sc.count());
        }
        int done = counts.getOrDefault("DONE", 0);
        int failed = counts.getOrDefault("FAILED", 0);
        int skipped = counts.getOrDefault("SKIPPED", 0);
        int pending = counts.getOrDefault("PENDING", 0);
        int running = counts.getOrDefault("RUNNING", 0);
        int deducted = counts.getOrDefault("DEDUCTED", 0);
        boolean inflight = pending + running + deducted > 0;

        // If claiming is stopped, any leftover PENDING rows are SKIPPED here (cancel raced a claim).
        if (plan.stopNewUnits() && pending > 0) {
            units.bulkSkipPending(planId, plan.statusReason() == null ? "plan stopped" : plan.statusReason(), now);
            skipped += pending;
            pending = 0;
            inflight = running + deducted > 0;
        }
        // Close any PREOCCUPIED order belonging to a just-skipped unit (claim/cancel race).
        orders.cancelOpenOrdersOfSkippedUnits(planId,
                plan.statusReason() == null ? "plan stopped" : plan.statusReason(), now);
        if (inflight) {
            plans.refreshCounts(planId, done, failed, skipped, now);
            return false; // claimed units must still finish on their bound version
        }

        String terminal;
        String reason;
        if (done == plan.totalUnits()) {
            terminal = "COMPLETED";
            reason = "全部 " + done + " 序号完成";
        } else if (plan.cancelRequested() && done > 0) {
            terminal = "PARTIAL";
            reason = "取消时已有 " + done + " 序号完成，奖励不回滚；未执行 " + skipped + "，失败 " + failed;
        } else if (plan.cancelRequested()) {
            terminal = "CANCELLED";
            reason = "计划取消；已完成 0，未执行 " + skipped;
        } else if (failed > 0) {
            terminal = "FAILED";
            reason = "存在失败序号 " + failed + "；已完成 " + done + "，未执行 " + skipped;
        } else {
            terminal = "PARTIAL";
            reason = "计划提前停止；已完成 " + done + "，未执行 " + skipped
                    + (plan.statusReason() != null ? "（" + plan.statusReason() + "）" : "");
        }
        int updated = plans.casTerminal(planId, terminal, reason, done, failed, skipped, now);
        return updated == 1 || plans.findByIdForUpdate(planId).orElseThrow().isTerminal();
    }

    /**
     * Player cancel races the worker: only PENDING units become SKIPPED. A unit whose
     * materials are already deducted keeps running and its reward is NOT rolled back.
     */
    @Transactional
    public BatchPlan cancelPlan(String planNo, long playerId, String reason) {
        Instant now = Instant.now(clock);
        BatchPlan plan = plans.findByNoForUpdate(planNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        if (plan.playerId() != playerId) {
            throw ApiException.forbidden("无权取消他人计划");
        }
        if (plan.isTerminal()) {
            return plan;
        }
        plans.markCancelRequested(plan.id(), now);
        int skipped = units.bulkSkipPending(plan.id(),
                reason == null || reason.isBlank() ? "player cancelled" : reason, now);
        log.info("plan {} cancel: {} not-started units skipped", planNo, skipped);
        tryFinalize(plan.id());
        return plans.findByNo(planNo).orElseThrow();
    }

    /** Mark a unit FAILED and drop its lease (used when a unit is genuinely unrecoverable). */
    @Transactional
    public void failUnit(long unitId, String reason) {
        Instant now = Instant.now(clock);
        units.casFailed(unitId, reason, now);
        PlanUnit u = units.findByIdForUpdate(unitId).orElseThrow();
        tryFinalize(u.planId());
    }

    // ---- crash recovery ----------------------------------------------------

    /**
     * Decide how to resume an expired-lease unit from durable state. Caller runs this AFTER
     * reacquiring the lease.
     */
    public RecoveryAction classifyRecovery(PlanUnit u) {
        if ("DEDUCTED".equals(u.status())) {
            return RecoveryAction.ISSUE_REWARD; // Tx1 done, possibly Tx2 too (issueRewards is idempotent)
        }
        // RUNNING: either crashed before Tx1 committed (no CONSUME rows) -> restart,
        // or after the DEDUCTED flip was lost (impossible — same tx). Check durable rows.
        boolean anyConsume = false;
        List<com.gameops.craft.domain.LedgerEntry> rows = ledger.listByRef(u.orderNo());
        for (var e : rows) {
            if ("CONSUME".equals(e.entryType())) {
                anyConsume = true;
            }
            if ("PRODUCE".equals(e.entryType())) {
                return RecoveryAction.ALL_DONE_WRITEBACK;
            }
        }
        return anyConsume ? RecoveryAction.ISSUE_REWARD : RecoveryAction.RESTART_DEDUCT;
    }

    public enum RecoveryAction { RESTART_DEDUCT, ISSUE_REWARD, ALL_DONE_WRITEBACK }

    /** Control-flow signal rolled back inside the deduction tx; converted by handleShortage. */
    static final class ShortageSignal extends RuntimeException {
        final long unitId;
        final long planId;
        ShortageSignal(long unitId, long planId, String message) {
            super(message);
            this.unitId = unitId;
            this.planId = planId;
        }
        @Override public Throwable fillInStackTrace() { return this; }
    }

    // ---- helpers -----------------------------------------------------------

    private Instant leaseExpiry(Instant now) {
        return now.plusSeconds(leaseSeconds);
    }

    private final long leaseSeconds = 30;

    private static String newUnitOrderNo(Clock clock) {
        return com.gameops.craft.common.DocNumbers.next("CU", clock);
    }

    static List<ItemQty> parseItems(String json) {
        try {
            return Json.MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("bad plan snapshot json", e);
        }
    }

    private static String writeJson(Object value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ItemQty> sortedByItem(List<ItemQty> items) {
        return items.stream().sorted(Comparator.comparing(ItemQty::getItemCode)).toList();
    }

    private static void validatePositive(List<ItemQty> items) {
        for (ItemQty it : items) {
            if (it.getItemCode() == null || it.getItemCode().isBlank() || it.getQty() <= 0) {
                throw ApiException.badRequest("RECIPE_INVALID_LINE", "配方行非法：" + it.getItemCode());
            }
        }
    }
}
