package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.CraftOrder;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.RecipeVersion;
import com.gameops.craft.repo.HoldRepository;
import com.gameops.craft.repo.IdempotencyRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.repo.RecipeRepository;
import com.gameops.craft.repo.RevokeRepository;
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
 * Non-transactional facade around CraftTxService implementing request-retry semantics.
 * Idempotency-Key ownership is a plain INSERT (unique key):
 *   first call reserves IN_FLIGHT, stores the DONE response on success;
 *   retries with the same key get the stored response byte-for-byte;
 *   a business-rule failure releases the key so the client can fix and retry;
 *   an IN_FLIGHT duplicate (parallel click / live retry) gets 409 RETRY_IN_FLIGHT.
 */
@Service
public class CraftService {

    private static final Logger log = LoggerFactory.getLogger(CraftService.class);

    private final CraftTxService tx;
    private final IdempotencyRepository idem;
    private final RecipeRepository recipes;
    private final InventoryRepository inventory;
    private final HoldRepository holds;
    private final OrderRepository orders;
    private final LedgerRepository ledger;
    private final RevokeRepository revokes;
    private final Clock clock;
    private final long inFlightTtlSeconds;

    public CraftService(CraftTxService tx, IdempotencyRepository idem, RecipeRepository recipes,
                        InventoryRepository inventory, HoldRepository holds, OrderRepository orders,
                        LedgerRepository ledger, RevokeRepository revokes, Clock clock,
                        @Value("${app.in-flight-ttl-seconds:300}") long inFlightTtlSeconds) {
        this.tx = tx;
        this.idem = idem;
        this.orders = orders;
        this.recipes = recipes;
        this.inventory = inventory;
        this.holds = holds;
        this.ledger = ledger;
        this.revokes = revokes;
        this.clock = clock;
        this.inFlightTtlSeconds = inFlightTtlSeconds;
    }

    // ---- player craft flow -------------------------------------------------

    public Map<String, Object> preoccupy(long playerId, long recipeId, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Instant now = Instant.now(clock);
        if (!idem.tryReserve(key, playerId, "PREOCCUPY", now)) {
            return replayOrReject(key);
        }
        try {
            CraftTxService.PreoccupyResult r = tx.preoccupy(playerId, recipeId);
            Map<String, Object> body = orderBody(r.order());
            body.put("boundVersionNo", r.version().versionNo());
            body.put("deadline", r.order().preoccupyDeadline());
            body.put("inputs", r.version().inputs());
            body.put("outputs", r.version().outputs());
            String json = writeJson(body);
            idem.complete(key, r.orderNo(), json, Instant.now(clock));
            return body;
        } catch (ApiException e) {
            // Rule failure: nothing was committed (tx rolled back). Allow a corrected retry.
            idem.delete(key);
            throw e;
        } catch (Exception e) {
            log.error("preoccupy failed for player {} recipe {}", playerId, recipeId, e);
            throw e;
        }
    }

    public Map<String, Object> commit(long playerId, String orderNo, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Instant now = Instant.now(clock);
        if (!idem.tryReserve(key, playerId, "COMMIT", now)) {
            return replayOrReject(key);
        }
        try {
            CraftOrder order = tx.commit(orderNo, playerId);
            RecipeVersion version = recipes.findVersionById(order.recipeVersionId()).orElseThrow();
            Map<String, Object> body = orderBody(order);
            body.put("boundVersionNo", version.versionNo());
            body.put("outputs", version.outputs());
            String json = writeJson(body);
            idem.complete(key, orderNo, json, Instant.now(clock));
            return body;
        } catch (ApiException e) {
            idem.delete(key);
            throw e;
        }
    }

    /** Cancel is naturally idempotent (CAS to terminal state); no key required. */
    public Map<String, Object> cancel(long playerId, String orderNo, String reason) {
        return orderBody(tx.cancel(orderNo, playerId, reason));
    }

    /** Used by the sweeper (per-order tx) and by timeout tests. */
    public boolean forceTimeout(String orderNo) {
        return tx.timeoutExpiredOrder(orderNo);
    }

    // ---- reads -------------------------------------------------------------

    /** Player-facing recipe list: current version + window + this player's material status. */
    public List<Map<String, Object>> catalog(long playerId) {
        return recipes.listHeaders().stream()
                .map(h -> preview(playerId, h.id()))
                .toList();
    }

    public Map<String, Object> preview(long playerId, long recipeId) {
        RecipeRepository.RecipeHeader header = recipes.findHeader(recipeId)
                .orElseThrow(() -> ApiException.notFound("RECIPE_NOT_FOUND", "配方不存在"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipeId", header.id());
        body.put("recipeCode", header.code());
        body.put("recipeName", header.name());
        body.put("headerStatus", header.status());

        Instant now = Instant.now(clock);
        RecipeVersion current = recipes.findCurrentPublished(recipeId).orElse(null);
        body.put("now", now);
        if (current == null) {
            body.put("craftable", false);
            body.put("reason", "NO_PUBLISHED_VERSION");
            return body;
        }
        List<ItemQty> inputs = current.inputs();
        Map<String, Long> balances = inventory.mapFor(playerId,
                inputs.stream().map(ItemQty::getItemCode).toList());
        Map<String, Long> held = new LinkedHashMap<>();
        for (HoldRepository.HoldRow h : holds.listByPlayer(playerId)) {
            held.merge(h.itemCode(), h.qty(), Long::sum);
        }
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        boolean enoughMaterials = true;
        for (ItemQty in : inputs) {
            long balance = balances.getOrDefault(in.getItemCode(), 0L);
            long locked = held.getOrDefault(in.getItemCode(), 0L);
            long available = balance; // balance column already excludes held (deducted at preoccupy)
            boolean enough = available >= in.getQty();
            if (!enough) {
                enoughMaterials = false;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("itemCode", in.getItemCode());
            line.put("need", in.getQty());
            line.put("balance", balance);
            line.put("inOtherOrders", locked);
            line.put("available", available);
            line.put("enough", enough);
            lines.add(line);
        }
        boolean windowOpen = "ACTIVE".equals(header.status()) && current.isOpenAt(now);
        boolean craftable = windowOpen && enoughMaterials;
        body.put("versionNo", current.versionNo());
        body.put("versionId", current.id());
        body.put("activityStart", current.startTime());
        body.put("activityEnd", current.endTime());
        body.put("activityOpen", current.isOpenAt(now));
        body.put("craftTimeoutSeconds", current.craftTimeoutSeconds());
        body.put("inputs", lines);
        body.put("outputs", current.outputs());
        body.put("craftable", craftable);
        return body;
    }

    /** Full material trail of one order: holds + every ledger line touching it. */
    public Map<String, Object> orderDetail(long playerId, String orderNo) {
        CraftOrder order = orders.findByNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "合成单不存在"));
        if (order.playerId() != playerId) {
            throw ApiException.forbidden("无权查看他人合成单");
        }
        Map<String, Object> body = orderBody(order);
        body.put("holds", holds.listByOrder(order.id()).stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemCode", h.itemCode());
            m.put("qty", h.qty());
            return m;
        }).toList());
        body.put("ledger", ledger.listByRef(orderNo));
        return body;
    }

    // ---- shared shaping ----------------------------------------------------

    public static Map<String, Object> orderBody(CraftOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", o.orderNo());
        m.put("recipeId", o.recipeId());
        m.put("recipeCode", o.recipeCode());
        m.put("recipeName", o.recipeName());
        m.put("boundVersionNo", o.versionNo());
        m.put("status", o.status());
        m.put("statusReason", o.statusReason());
        m.put("preoccupyDeadline", o.preoccupyDeadline());
        m.put("committedAt", o.committedAt());
        m.put("closedAt", o.closedAt());
        m.put("revokeRefNo", o.revokeRefNo());
        m.put("createdAt", o.createdAt());
        return m;
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
            // Previous holder crashed; safe to reclaim (its tx is gone with the process).
            idem.delete(key);
            throw ApiException.conflict("IDEMPOTENCY_STALE", "上次请求中断，请重新发起");
        }
        throw ApiException.conflict("RETRY_IN_FLIGHT", "相同请求正在处理，请勿重复提交");
    }

    private static String writeJson(Object value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private static String requireKey(String key) {        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "必须携带 Idempotency-Key（客户端生成的唯一请求号）");
        }
        if (key.length() > 80) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_TOO_LONG", "幂等键长度不能超过 80");
        }
        return key;
    }
}
