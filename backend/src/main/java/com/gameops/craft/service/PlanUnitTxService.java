package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.CraftOrder;
import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.domain.CraftPlanUnit;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.RecipeVersion;
import com.gameops.craft.repo.HoldRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.repo.RecipeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional primitives for one batch-plan unit.
 *
 * The unit lifecycle deliberately spans short transactions so both required
 * crash windows are durable and independently recoverable:
 *
 *   claim():  lease a PENDING unit (after the start gate) or reclaim an expired
 *             LEASED unit of a crashed worker.
 *   deduct(): PREOCCUPY order + conditional material UPDATE + CONSUME rows.
 *             Crash after this commit but before reward -> deducted, not rewarded.
 *   reward(): CAS order PREOCCUPIED->COMMITTED + PRODUCE rows + unit.rewarded_at.
 *             Crash after this commit but before writeback -> rewarded, unit LEASED.
 *   writeBackDone(): LEASED(+this token) -> DONE.
 *
 * Recovery after lease expiry re-reads the durable state and performs only the
 * missing side, at most once: craft_order CAS and the ledger unique keys are
 * the at-most-once anchors; the unit's lease-token CAS prevents a superseded
 * dead worker from writing back.
 */
@Service
public class PlanUnitTxService {

    private static final Logger log = LoggerFactory.getLogger(PlanUnitTxService.class);

    /** Plan orders are not player-committed; keep holds well beyond worker lease life. */
    static final int PLAN_ORDER_DEADLINE_SECONDS = 3600;

    private final PlanRepository plans;
    private final PlanUnitRepository units;
    private final RecipeRepository recipes;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final HoldRepository holds;
    private final LedgerRepository ledger;
    private final Clock clock;

    public PlanUnitTxService(PlanRepository plans, PlanUnitRepository units, RecipeRepository recipes,
                             InventoryRepository inventory, OrderRepository orders,
                             HoldRepository holds, LedgerRepository ledger, Clock clock) {
        this.plans = plans;
        this.units = units;
        this.recipes = recipes;
        this.inventory = inventory;
        this.orders = orders;
        this.holds = holds;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Result of a claim attempt: either a leased unit or the reason none was claimed. */
    public record ClaimResult(CraftPlanUnit unit, boolean recovered, String stopReason) {
        static ClaimResult of(CraftPlanUnit u, boolean recovered) {
            return new ClaimResult(u, recovered, null);
        }
        static ClaimResult empty(String reason) {
            return new ClaimResult(null, false, reason);
        }
    }

    /**
     * Lease one unit for this worker, all in one transaction.
     *
     * Lock order is always plan-header row -> unit row, identical for workers,
     * player cancel and sweeps, so concurrent paths serialize without deadlock.
     *
     * Priority 1 — recovery: an expired lease of a crashed worker is reclaimed
     * regardless of stop/cancel (already-preoccupied positions must always be
     * settled on their bound version).
     *
     * Priority 2 — new position: with the plan header locked, evaluate the start
     * gate (recipe ACTIVE + frozen version window open). If closed, the plan is
     * stopped and pending units are skipped; otherwise the lowest PENDING unit is
     * row-locked and leased with a gated CAS that re-checks plan state.
     */
    @Transactional
    public ClaimResult claim(long planId, String owner, String token, long leaseSeconds) {
        Instant now = Instant.now(clock);
        Instant expires = now.plusSeconds(leaseSeconds);

        // Lock the plan header FIRST (common lock order for claim/cancel/stop).
        CraftPlan plan = plans.findByIdForUpdate(planId).orElse(null);
        if (plan == null) {
            return ClaimResult.empty("PLAN_NOT_FOUND");
        }

        // 1) Recovery always wins: expired lease of a crashed worker is settled
        //    even after stop/cancel (its materials were already deducted).
        List<CraftPlanUnit> expired = units.findExpiredLeasedForClaim(planId, now, 1);
        if (!expired.isEmpty()) {
            CraftPlanUnit u = expired.get(0);
            if (units.casLeaseExpired(u.id(), owner, token, now, expires) == 0) {
                return ClaimResult.empty("LEASE_TAKEN");
            }
            return ClaimResult.of(units.findByIdForUpdate(u.id()).orElseThrow(), true);
        }

        if (plan.isTerminal()) {
            return ClaimResult.empty("PLAN_TERMINAL:" + plan.status());
        }

        // 2) Start gate for new positions; on close, stop + skip drain here.
        String gateReason = startGateReason(
                recipes.findHeader(plan.recipeId()).orElse(null),
                recipes.findVersionById(plan.recipeVersionId()).orElse(null), now);
        if (gateReason != null) {
            plans.casRequestStop(planId, gateReason);
            plan = plans.findByIdForUpdate(planId).orElseThrow();
        }
        if (plan.stopFlag() || plan.cancelledAt() != null) {
            String reason = plan.cancelledAt() != null
                    ? "PLAYER_CANCELLED"
                    : (plan.stopReason() == null ? "PLAN_STOPPED" : plan.stopReason());
            units.skipAllPending(planId, reason, now);
            return ClaimResult.empty("STOPPED:" + reason);
        }

        // 3) Lease the lowest pending unit; gated CAS re-validates plan state.
        CraftPlanUnit next = units.lockNextPending(planId).orElse(null);
        if (next == null) {
            return ClaimResult.empty("NO_PENDING");
        }
        if (units.casLeasePendingGated(next.id(), owner, token, now, expires) == 0) {
            return ClaimResult.empty("LEASE_TAKEN");
        }
        return ClaimResult.of(units.findByIdForUpdate(next.id()).orElseThrow(), false);
    }

    private static String startGateReason(RecipeRepository.RecipeHeader header,
                                          RecipeVersion version, Instant now) {
        if (header == null) {
            return "RECIPE_NOT_FOUND";
        }
        if ("CLOSED".equals(header.status())) {
            return "RECIPE_CLOSED";
        }
        if (version == null) {
            return "VERSION_MISSING";
        }
        // The plan's frozen version can be PUBLISHED or ARCHIVED (a newer version
        // was published after plan creation) — either is valid for a bound plan;
        // plan units never re-bind to a newer version.
        if (!"PUBLISHED".equals(version.status()) && !"ARCHIVED".equals(version.status())) {
            return "VERSION_NOT_USABLE";
        }
        if (!version.isOpenAt(now)) {
            return "ACTIVITY_ENDED";
        }
        return null;
    }

    /**
     * First durable window. For a fresh unit: deduct materials atomically with
     * the conditional UPDATE (WHERE qty >= need), create holds and CONSUME rows,
     * and bind the ordinary craft order — all atomic. For a resumed unit the
     * order already exists (crash after deduct) and this is a pure no-op read.
     *
     * Material insufficiency rolls this whole tx back (nothing deducted); the
     * caller then requests stop and skips this never-started unit.
     */
    @Transactional
    public void deduct(long unitId, String token) {
        Instant now = Instant.now(clock);
        Locked lu = lockPlanThenUnit(unitId, token);
        CraftPlan plan = lu.plan();
        CraftPlanUnit unit = lu.unit();
        if (unit.orderNo() != null) {
            return; // recovery: materials already deducted before the crash
        }
        RecipeVersion version = recipes.findVersionById(plan.recipeVersionId())
                .orElseThrow(() -> new IllegalStateException("bound version missing"));

        List<ItemQty> inputs = parseItems(plan.inputsJson());
        String orderNo = DocNumbers.next("PU", clock);
        Instant deadline = now.plusSeconds(PLAN_ORDER_DEADLINE_SECONDS);
        long orderId = orders.insertWithPlan(orderNo, plan.playerId(), plan.recipeId(),
                version.id(), deadline, now, plan.planNo(), unit.unitNo());

        for (ItemQty in : inputs) {
            int changed = inventory.deductIfEnough(plan.playerId(), in.getItemCode(), in.getQty(), now);
            if (changed == 0) {
                // Rollback removes the order/holds/CONSUME rows as well.
                throw ApiException.conflict("MATERIAL_INSUFFICIENT",
                        "材料不足：" + in.getItemCode() + " 需要 " + in.getQty()
                                + "，序号 " + unit.unitNo() + " 不启动");
            }
            holds.create(orderId, plan.playerId(), in.getItemCode(), in.getQty(), now);
            ledger.insert(orderNo, plan.playerId(), in.getItemCode(), "CONSUME", -in.getQty(),
                    null, plan.planNo(), unit.unitNo(), "POSTED",
                    "批量合成扣料 计划" + plan.planNo() + "#" + unit.unitNo()
                            + " v" + plan.versionNo(), now);
        }
        if (units.bindOrder(unit.id(), orderNo, now) == 0) {
            throw new IllegalStateException("unit order binding lost for " + unit.id());
        }
    }

    /**
     * Second durable window. Commit the bound order on its frozen version.
     * craft_order CAS (PREOCCUPIED->COMMITTED) plus the PRODUCE ledger unique key
     * guarantee rewards are issued at most once even on replay. rewarded_at is
     * stamped in the same tx as the third durable checkpoint.
     */
    @Transactional
    public void reward(long unitId, String token) {
        Instant now = Instant.now(clock);
        Locked lu = lockPlanThenUnit(unitId, token);
        CraftPlan plan = lu.plan();
        CraftPlanUnit unit = lu.unit();
        if (unit.orderNo() == null) {
            throw new IllegalStateException("unit " + unitId + " rewarded before deduct");
        }
        CraftOrder order = orders.findByNoForUpdate(unit.orderNo())
                .orElseThrow(() -> new IllegalStateException("bound order missing"));

        if ("COMMITTED".equals(order.status())) {
            // Crash window 2 recovery: outputs already issued; only checkpoint.
            units.markRewarded(unit.id(), now);
            return;
        }
        if (!"PREOCCUPIED".equals(order.status())) {
            throw new IllegalStateException("plan unit order in unexpected status " + order.status());
        }

        var holdRows = holds.lockByOrder(order.id());
        RecipeVersion version = recipes.findVersionById(plan.recipeVersionId())
                .orElseThrow(() -> new IllegalStateException("bound version missing"));

        int cas = orders.casCommit(order.id(), now, now);
        if (cas == 0) {
            // Cannot normally happen (plan deadline is long); do not issue rewards.
            throw ApiException.conflict("ORDER_COMMIT_RACE", "序号订单提交竞争失败");
        }
        for (HoldRepository.HoldRow h : holdRows) {
            holds.markReleased(h.id(), now);
        }
        for (ItemQty out : parseItems(plan.outputsJson())) {
            inventory.credit(plan.playerId(), out.getItemCode(), out.getQty(), now);
            // Unique key (ref_no,player,item,type): double PRODUCE impossible.
            ledger.insert(order.orderNo(), plan.playerId(), out.getItemCode(), "PRODUCE",
                    out.getQty(), null, plan.planNo(), unit.unitNo(), "POSTED",
                    "批量合成产出 计划" + plan.planNo() + "#" + unit.unitNo()
                            + " v" + version.versionNo(), now);
        }
        units.markRewarded(unit.id(), now);
    }

    /**
     * Final writeback. LEASED + own token -> DONE. Zero rows means the lease was
     * reclaimed while this worker was stalled; the recovery owner now owns it.
     */
    @Transactional
    public boolean writeBackDone(long unitId, String token) {
        return units.casDone(unitId, token, Instant.now(clock)) == 1;
    }

    @Transactional
    public void writeBackFailed(long unitId, String token, String reason) {
        units.casFail(unitId, token, reason, Instant.now(clock));
    }

    /** Skip a leased, never-ordered unit whose start failed (materials insufficient). */
    @Transactional
    public int skipLeasedWithoutOrder(long unitId, String token, String reason) {
        return units.skipLeasedWithoutOrder(unitId, token, reason, Instant.now(clock));
    }

    @Transactional
    public void renewLease(long unitId, String token, long leaseSeconds) {
        units.renewLease(unitId, token, Instant.now(clock).plusSeconds(leaseSeconds));
    }

    /**
     * Uniform lock order for every mutating unit path: plan header row first,
     * then the unit row. claim/cancel/finalize all take the plan row first too,
     * so cross-transaction deadlocks are impossible. Verifies the caller still
     * owns the live lease (a reclaimed unit belongs to its recovery worker).
     */
    private Locked lockPlanThenUnit(long unitId, String token) {
        CraftPlanUnit peek = units.findById(unitId)
                .orElseThrow(() -> ApiException.notFound("UNIT_NOT_FOUND", "计划序号不存在"));
        CraftPlan plan = plans.findByIdForUpdate(peek.planId())
                .orElseThrow(() -> new IllegalStateException("plan missing"));
        CraftPlanUnit unit = units.findByIdForUpdate(unitId).orElseThrow();
        if (!"LEASED".equals(unit.status()) || !token.equals(unit.leaseToken())) {
            throw ApiException.conflict("LEASE_LOST", "序号租约已被回收：" + unit.unitNo());
        }
        return new Locked(plan, unit);
    }

    private record Locked(CraftPlan plan, CraftPlanUnit unit) {}

    static List<ItemQty> parseItems(String json) {
        try {
            return Json.MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("bad plan snapshot json", e);
        }
    }
}
