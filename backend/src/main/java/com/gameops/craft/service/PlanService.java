package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.BatchPlan;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.domain.RecipeVersion;
import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.IdempotencyRepository;
import com.gameops.craft.repo.LedgerRepository;
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

/**
 * Non-transactional facade for batch plans: request-retry semantics on Idempotency-Key.
 *
 * Concurrent creates with the SAME key all race on one INSERT into idempotency_record
 * (unique key): exactly one creates a plan; the rest either get 409 RETRY_IN_FLIGHT while
 * it is being created, or the exact stored response (same planNo) once DONE.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PlanTxService tx;
    private final IdempotencyRepository idem;
    private final BatchPlanRepository plans;
    private final PlanUnitRepository units;
    private final RecipeRepository recipes;
    private final LedgerRepository ledger;
    private final Clock clock;
    private final long inFlightTtlSeconds;

    public PlanService(PlanTxService tx, IdempotencyRepository idem, BatchPlanRepository plans,
                       PlanUnitRepository units, RecipeRepository recipes, LedgerRepository ledger,
                       Clock clock,
                       @Value("${app.in-flight-ttl-seconds:300}") long inFlightTtlSeconds) {
        this.tx = tx;
        this.idem = idem;
        this.plans = plans;
        this.units = units;
        this.recipes = recipes;
        this.ledger = ledger;
        this.clock = clock;
        this.inFlightTtlSeconds = inFlightTtlSeconds;
    }

    public Map<String, Object> createPlan(long playerId, long recipeId, Integer totalUnitsRaw,
                                          String idempotencyKey) {
        int totalUnits = totalUnitsRaw == null ? 0 : totalUnitsRaw;
        if (totalUnits < PlanTxService.MIN_UNITS || totalUnits > PlanTxService.MAX_UNITS) {
            throw ApiException.badRequest("PLAN_SIZE_INVALID",
                    "批量次数必须在 " + PlanTxService.MIN_UNITS + "–" + PlanTxService.MAX_UNITS + " 之间");
        }
        String key = requireKey(idempotencyKey);
        Instant now = Instant.now(clock);
        if (!idem.tryReserve(key, playerId, "PLAN_CREATE", now)) {
            return replayOrReject(key);
        }
        String planNo = DocNumbers.next("PL", clock);
        try {
            PlanTxService.CreatedPlan r = tx.createPlan(planNo, playerId, recipeId, totalUnits, now);
            Map<String, Object> body = planBody(r.plan());
            body.put("boundVersionNo", r.version().versionNo());
            body.put("inputs", r.version().inputs());
            body.put("outputs", r.version().outputs());
            String json;
            try {
                json = Json.MAPPER.writeValueAsString(body);
            } catch (Exception ex) {
                throw new IllegalStateException("failed to serialize plan response", ex);
            }
            idem.complete(key, planNo, json, Instant.now(clock));
            return body;
        } catch (ApiException e) {
            idem.delete(key); // rule failure: nothing was committed; allow corrected retry
            throw e;
        } catch (Exception e) {
            idem.delete(key);
            log.error("createPlan failed player={} recipe={}", playerId, recipeId, e);
            throw e;
        }
    }

    public Map<String, Object> cancel(long playerId, String planNo, String reason) {
        return planBody(tx.cancelPlan(planNo, playerId, reason));
    }

    // ---- reads -------------------------------------------------------------

    public List<Map<String, Object>> myPlans(long playerId) {
        return plans.listByPlayer(playerId, 100).stream().map(PlanService::planBody).toList();
    }

    /** Live progress + per-unit timeline; every field is recomputed from durable state. */
    public Map<String, Object> planDetail(long playerId, String planNo) {
        BatchPlan plan = plans.findByNo(planNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        if (plan.playerId() != playerId) {
            throw ApiException.forbidden("无权查看他人计划");
        }
        return detail(plan);
    }

    /** Operator-side detail (no ownership check). */
    public Map<String, Object> detail(BatchPlan plan) {
        Map<String, Object> body = planBody(plan);
        RecipeVersion v = recipes.findVersionById(plan.recipeVersionId()).orElse(null);
        body.put("boundVersionNo", v == null ? null : v.versionNo());
        body.put("snapshotInputs", v == null ? List.of() : parseItems(plan.inputsJson()));
        body.put("snapshotOutputs", v == null ? List.of() : parseItems(plan.outputsJson()));
        body.put("units", units.listByPlan(plan.id()).stream().map(PlanService::unitBody).toList());
        body.put("ledger", ledger.listByPlan(plan.planNo()));
        return body;
    }

    public static Map<String, Object> planBody(BatchPlan p) {
        int settled = p.completedCount() + p.failedCount() + p.skippedCount();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planNo", p.planNo());
        m.put("recipeId", p.recipeId());
        m.put("recipeVersionId", p.recipeVersionId());
        m.put("boundVersionNo", p.versionNo());
        m.put("totalUnits", p.totalUnits());
        m.put("status", p.status());
        m.put("statusReason", p.statusReason());
        m.put("stopNewUnits", p.stopNewUnits());
        m.put("cancelRequested", p.cancelRequested());
        m.put("completedCount", p.completedCount());
        m.put("failedCount", p.failedCount());
        m.put("skippedCount", p.skippedCount());
        m.put("executedCount", p.completedCount());
        m.put("notRunCount", p.totalUnits() - p.completedCount() - p.failedCount() - p.skippedCount());
        m.put("settledCount", settled);
        m.put("createdAt", p.createdAt());
        m.put("finishedAt", p.finishedAt());
        return m;
    }

    public static Map<String, Object> unitBody(PlanUnit u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unitNo", u.unitNo());
        m.put("orderNo", u.orderNo());
        m.put("status", u.status());
        m.put("statusReason", u.statusReason());
        m.put("leaseOwner", u.leaseOwner());
        m.put("leaseExpiresAt", u.leaseExpiresAt());
        m.put("attempts", u.attempts());
        m.put("startedAt", u.startedAt());
        m.put("deductedAt", u.deductedAt());
        m.put("completedAt", u.completedAt());
        return m;
    }

    private static List<ItemQty> parseItems(String json) {
        return PlanTxService.parseItems(json);
    }

    private Map<String, Object> replayOrReject(String key) {
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
        if (row.updatedAt().isBefore(Instant.now(clock).minus(Duration.ofSeconds(inFlightTtlSeconds)))) {
            idem.delete(key);
            throw ApiException.conflict("IDEMPOTENCY_STALE", "上次请求中断，请重新发起");
        }
        throw ApiException.conflict("RETRY_IN_FLIGHT", "相同批量计划请求正在处理，请勿重复提交");
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "必须携带 Idempotency-Key");
        }
        if (key.length() > 80) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_TOO_LONG", "幂等键长度不能超过 80");
        }
        return key;
    }
}
