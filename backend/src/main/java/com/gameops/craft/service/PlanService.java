package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.domain.CraftPlanUnit;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.RecipeVersion;
import com.gameops.craft.repo.IdempotencyRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.repo.RecipeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch-plan facade: idempotent creation, player cancel, worker lease execution,
 * and plan-level finalization. All correctness lives in DB transactions,
 * row locks, CAS updates and unique constraints — never in JVM state.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 100;

    private final PlanRepository plans;
    private final PlanUnitRepository units;
    private final RecipeRepository recipes;
    private final IdempotencyRepository idem;
    private final PlanUnitTxService unitTx;
    private final Clock clock;
    private final long inFlightTtlSeconds;
    private final long leaseSeconds;

    public PlanService(PlanRepository plans, PlanUnitRepository units, RecipeRepository recipes,
                       IdempotencyRepository idem, PlanUnitTxService unitTx, Clock clock,
                       @Value("${app.in-flight-ttl-seconds:300}") long inFlightTtlSeconds,
                       @Value("${app.plan.lease-seconds:30}") long leaseSeconds) {
        this.plans = plans;
        this.units = units;
        this.recipes = recipes;
        this.idem = idem;
        this.unitTx = unitTx;
        this.clock = clock;
        this.inFlightTtlSeconds = inFlightTtlSeconds;
        this.leaseSeconds = leaseSeconds;
    }

    public long leaseSeconds() {
        return leaseSeconds;
    }

    // ---- creation ----------------------------------------------------------

    /**
     * Create a plan with an Idempotency-Key. The key is reserved with a plain
     * INSERT (unique constraint): 32 concurrent submits with the same key can at
     * most produce ONE plan. Losers wait briefly for the winner's result and
     * replay it; they never create a second plan and never call single-craft.
     */
    public Map<String, Object> createPlan(long playerId, long recipeId, int count,
                                          String idempotencyKey) {
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw ApiException.badRequest("PLAN_COUNT_OUT_OF_RANGE",
                    "批量次数必须在 " + MIN_COUNT + "–" + MAX_COUNT + " 之间");
        }
        String key = requireKey(idempotencyKey);
        Instant now = Instant.now(clock);
        if (!idem.tryReserve(key, playerId, "PLAN_CREATE", now)) {
            return replayOrRejectPlan(key);
        }
        try {
            CraftPlan plan = createInTx(playerId, recipeId, count);
            Map<String, Object> body = planBody(plan);
            body.put("inputs", PlanUnitTxService.parseItems(plan.inputsJson()));
            body.put("outputs", PlanUnitTxService.parseItems(plan.outputsJson()));
            String json = writeJson(body);
            idem.complete(key, plan.planNo(), json, Instant.now(clock));
            return body;
        } catch (ApiException e) {
            idem.delete(key);
            throw e;
        } catch (Exception e) {
            log.error("createPlan failed player {} recipe {} count {}", playerId, recipeId, count, e);
            throw e;
        }
    }

    /**
     * Freeze header/version/snapshot exactly like single preoccupy: lock recipe
     * header + current published version, validate window, snapshot inputs/outputs.
     * Materials are NOT deducted here — each worker unit deducts when it starts.
     */
    @Transactional
    public CraftPlan createInTx(long playerId, long recipeId, int count) {
        Instant now = Instant.now(clock);
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
                    "活动未在有效期内（结束 " + version.endTime() + "）");
        }
        List<ItemQty> inputs = version.inputs().stream()
                .sorted(java.util.Comparator.comparing(ItemQty::getItemCode)).toList();
        List<ItemQty> outputs = version.outputs();
        if (inputs.isEmpty() || outputs.isEmpty() || version.craftTimeoutSeconds() <= 0) {
            throw ApiException.badRequest("RECIPE_INCOMPLETE", "发布版本缺少材料/产出/超时配置");
        }

        String planNo = DocNumbers.next("BP", clock);
        String inputsJson = writeJson(inputs);
        String outputsJson = writeJson(outputs);
        long planId = plans.insert(planNo, playerId, recipeId, version.id(), count,
                inputsJson, outputsJson, now);
        units.batchInsert(planId, planNo, count, now);
        return plans.findByNo(planNo).orElseThrow();
    }

    // ---- cancel ------------------------------------------------------------

    /**
     * Player cancel races worker claiming. Both paths lock the plan header row
     * (common first lock); the CAS sets stop_flag + cancelled_at:
     *  - units still PENDING (never started) are marked SKIPPED immediately;
     *  - LEASED units keep running to settlement and their rewards are never
     *    rolled back; the plan's terminal CANCELLED is written by finalize after
     *    the last leased unit finishes, so its completed count is accurate;
     *  - a second cancel or cancel-after-finish is an idempotent no-op replay.
     */
    @Transactional
    public Map<String, Object> cancelPlan(long playerId, String planNo, String reason) {
        Instant now = Instant.now(clock);
        CraftPlan plan = plans.findByNoForUpdate(planNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        if (plan.playerId() != playerId) {
            throw ApiException.forbidden("无权取消他人计划");
        }
        if (plan.cancelledAt() != null || "CANCELLED".equals(plan.status())) {
            return maybeFinalizeAndBody(plans.findByNo(planNo).orElseThrow(), now);
        }
        if (plan.isTerminal()) {
            // Already COMPLETED/PARTIAL/FAILED: nothing unstarted left to cancel.
            return planBody(plan);
        }
        int cas = plans.casCancel(plan.id(), now);
        if (cas == 0) {
            // Concurrent cancel already set the flag; settle through finalize.
            return maybeFinalizeAndBody(plans.findByNo(planNo).orElseThrow(), now);
        }
        // Positions that never started are skipped now. Leased units finish and
        // are not rolled back; finalize flips the header to CANCELLED afterwards.
        int skipped = units.skipAllPending(plan.id(),
                reason == null || reason.isBlank() ? "PLAYER_CANCELLED" : reason, now);
        log.info("plan {} cancelled by player {}; {} pending units skipped", planNo, playerId, skipped);
        CraftPlan fresh = plans.findByNo(planNo).orElseThrow();
        return maybeFinalizeAndBody(fresh, now);
    }

    // ---- worker execution --------------------------------------------------

    public PlanUnitTxService.ClaimResult claim(long planId, String owner, String token) {
        return unitTx.claim(planId, owner, token, leaseSeconds);
    }

    /**
     * Execute one claimed unit end-to-end. Each phase is its own transaction so
     * a crash leaves exactly one durable, recoverable state:
     *
     *   deduct tx: atomic material deduction + CONSUME + bind order
     *   reward tx: order CAS commit + PRODUCE + rewarded_at
     *   done tx  : lease-token CAS writeback DONE
     *
     * The start gate (activity window / recipe status) is evaluated in claim();
     * material shortage surfaces in deduct(): this unit never started (its tx
     * rolled back), so it is SKIPPED and the plan is stopped from new positions.
     * Already pre-occupied units are not affected and finish on the bound version.
     *
     * @return outcome used by the worker for logging; the DB is the real record
     */
    public UnitOutcome executeUnit(long planId, long unitId, String owner, String token) {
        // Window 1 — deduct (or resume when an order was already bound).
        try {
            unitTx.deduct(unitId, token);
        } catch (ApiException e) {
            if (!"MATERIAL_INSUFFICIENT".equals(e.getCode())) {
                unitTx.writeBackFailed(unitId, token, e.getCode() + ": " + e.getMessage());
                maybeFinalize(planId, Instant.now(clock));
                return new UnitOutcome(unitId, "FAILED", false);
            }
            // Last materials were consumed by a sibling unit: this position never
            // started. Stop further positions; skip this empty-lease unit.
            requestStop(planId, "MATERIAL_INSUFFICIENT");
            int skipped = unitTx.skipLeasedWithoutOrder(unitId, token, "MATERIAL_INSUFFICIENT");
            if (skipped == 0) {
                // Raced a recovery that bound an order: settle it normally.
                unitTx.reward(unitId, token);
                unitTx.writeBackDone(unitId, token);
            }
            maybeFinalize(planId, Instant.now(clock));
            return new UnitOutcome(unitId, "SKIPPED:MATERIAL_INSUFFICIENT", false);
        }
        // Window 2 — issue rewards exactly once (order CAS + unique PRODUCE).
        unitTx.reward(unitId, token);
        // Window 3 — writeback; a reclaimed lease makes us a no-op bystander.
        boolean done = unitTx.writeBackDone(unitId, token);
        if (!done) {
            log.warn("unit {} writeback lost (lease reclaimed); recovery owner finishes it", unitId);
            return new UnitOutcome(unitId, "WRITEBACK_LOST", false);
        }
        maybeFinalize(planId, Instant.now(clock));
        return new UnitOutcome(unitId, "DONE", true);
    }

    /** Stop new positions without touching leased/done units. */
    @Transactional
    public void requestStop(long planId, String reason) {
        plans.casRequestStop(planId, reason);
    }

    /**
     * Finalize a plan once no LEASED units remain. Counters are read under the
     * plan row lock; the terminal status is a CAS out of RUNNING so concurrent
     * workers finalize at most once:
     *   all total DONE                 -> COMPLETED
     *   cancel won                     -> CANCELLED (decision preserved)
     *   some DONE                      -> PARTIAL
     *   no DONE, some FAILED           -> FAILED
     *
     * When the plan is stopped/cancelled and no unit is still LEASED, any
     * PENDING rows (positions that must never start) are swept to SKIPPED here
     * under the same lock, so a plan whose last worker hit the stop gate still
     * reaches a terminal state without waiting for another claim round.
     */
    @Transactional
    public CraftPlan maybeFinalize(long planId, Instant now) {
        CraftPlan plan = plans.findByIdForUpdate(planId).orElse(null);
        if (plan == null || plan.isTerminal()) {
            return plan;
        }
        long leased = units.countByStatus(planId, "LEASED");
        if (leased > 0) {
            return plan;
        }
        long pending = units.countByStatus(planId, "PENDING");
        boolean cancelRequested = plan.cancelledAt() != null;
        if (pending > 0) {
            if (!plan.stopFlag() && !cancelRequested) {
                return plan; // workers still active
            }
            String reason = cancelRequested
                    ? "PLAYER_CANCELLED"
                    : (plan.stopReason() == null ? "PLAN_STOPPED" : plan.stopReason());
            units.skipAllPending(planId, reason, now);
        }
        int done = (int) units.countByStatus(planId, "DONE");
        int skipped = (int) units.countByStatus(planId, "SKIPPED");
        int failed = (int) units.countByStatus(planId, "FAILED");
        String status;
        if (cancelRequested) {
            status = "CANCELLED";
        } else if (done == plan.totalCount()) {
            status = "COMPLETED";
        } else if (done == 0 && failed > 0) {
            status = "FAILED";
        } else {
            status = "PARTIAL";
        }
        plans.casFinalize(planId, status, done, skipped, failed, now);
        return plans.findByNo(plan.planNo()).orElseThrow();
    }

    private Map<String, Object> maybeFinalizeAndBody(CraftPlan plan, Instant now) {
        CraftPlan p = maybeFinalize(plan.id(), now);
        return planBody(p == null ? plan : p);
    }

    // ---- reads -------------------------------------------------------------

    public Map<String, Object> planDetail(long playerId, String planNo) {
        CraftPlan plan = plans.findByNo(planNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        if (plan.playerId() != playerId) {
            throw ApiException.forbidden("无权查看他人计划");
        }
        return bodyWithUnits(plan);
    }

    public Map<String, Object> planDetailOps(String planNo) {
        CraftPlan plan = plans.findByNo(planNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        return bodyWithUnits(plan);
    }

    private Map<String, Object> bodyWithUnits(CraftPlan plan) {
        Map<String, Object> body = planBody(plan);
        List<CraftPlanUnit> rows = units.listByPlan(plan.id());
        body.put("units", rows.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("unitNo", u.unitNo());
            m.put("status", u.status());
            m.put("orderNo", u.orderNo());
            m.put("attempts", u.attempts());
            m.put("leaseOwner", u.leaseOwner());
            m.put("leaseExpiresAt", u.leaseExpiresAt());
            m.put("consumedAt", u.consumedAt());
            m.put("rewardedAt", u.rewardedAt());
            m.put("finishedAt", u.finishedAt());
            m.put("skipReason", u.skipReason());
            m.put("failReason", u.failReason());
            return m;
        }).toList());
        return body;
    }

    public List<Map<String, Object>> listPlayerPlans(long playerId, int limit) {
        return plans.listByPlayer(playerId, limit).stream().map(PlanService::planBody).toList();
    }

    public List<Map<String, Object>> listRecentPlans(int limit) {
        return plans.listRecent(limit).stream().map(PlanService::planBody).toList();
    }

    public List<Map<String, Object>> listAnomalousPlans(int limit) {
        return plans.listAnomalous(limit).stream().map(PlanService::planBody).toList();
    }

    public static Map<String, Object> planBody(CraftPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planNo", p.planNo());
        m.put("playerId", p.playerId());
        m.put("recipeId", p.recipeId());
        m.put("recipeCode", p.recipeCode());
        m.put("recipeName", p.recipeName());
        m.put("boundVersionId", p.recipeVersionId());
        m.put("boundVersionNo", p.versionNo());
        m.put("totalCount", p.totalCount());
        m.put("status", p.status());
        m.put("stopFlag", p.stopFlag());
        m.put("stopReason", p.stopReason());
        m.put("completedCount", p.completedCount());
        m.put("skippedCount", p.skippedCount());
        m.put("failedCount", p.failedCount());
        m.put("notStartedCount",
                p.totalCount() - p.completedCount() - p.skippedCount() - p.failedCount());
        m.put("cancelledAt", p.cancelledAt());
        m.put("finishedAt", p.finishedAt());
        m.put("createdAt", p.createdAt());
        return m;
    }

    // ---- idempotent replay (plan variant) ----------------------------------

    private Map<String, Object> replayOrRejectPlan(String key) {
        IdempotencyRepository.IdemRow row = idem.find(key)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE", "请求处理中，请重试"));
        if ("DONE".equals(row.status())) {
            try {
                JsonNode node = Json.MAPPER.readTree(row.responseJson());
                @SuppressWarnings("unchecked")
                Map<String, Object> replay = Json.MAPPER.convertValue(node, Map.class);
                replay.put("replayed", true);
                return replay;
            } catch (Exception e) {
                throw new IllegalStateException("bad stored idem response", e);
            }
        }
        // Concurrent same-key create: wait briefly for the winner then replay.
        // Guarantees the 32-way concurrent create surfaces the single plan to all.
        Instant deadline = Instant.now(clock).plus(Duration.ofSeconds(5));
        while (Instant.now(clock).isBefore(deadline)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            IdempotencyRepository.IdemRow now2 = idem.find(key).orElse(null);
            if (now2 != null && "DONE".equals(now2.status())) {
                try {
                    JsonNode node = Json.MAPPER.readTree(now2.responseJson());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> replay = Json.MAPPER.convertValue(node, Map.class);
                    replay.put("replayed", true);
                    return replay;
                } catch (Exception e) {
                    throw new IllegalStateException("bad stored idem response", e);
                }
            }
            if (now2 == null) {
                // Winner failed and released the key; caller may retry with same key.
                throw ApiException.conflict("IDEMPOTENCY_STALE", "上次请求中断，请重新发起");
            }
        }
        if (row.updatedAt().isBefore(Instant.now(clock).minus(Duration.ofSeconds(inFlightTtlSeconds)))) {
            idem.delete(key);
            throw ApiException.conflict("IDEMPOTENCY_STALE", "上次请求中断，请重新发起");
        }
        throw ApiException.conflict("RETRY_IN_FLIGHT", "相同请求正在处理，请勿重复提交");
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "必须携带 Idempotency-Key（客户端生成的唯一请求号）");
        }
        if (key.length() > 80) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_TOO_LONG", "幂等键长度不能超过 80");
        }
        return key;
    }

    private static String writeJson(Object value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    /** Outcome of one worker unit execution (for logs/metrics). */
    public record UnitOutcome(long unitId, String result, boolean done) {}
}
