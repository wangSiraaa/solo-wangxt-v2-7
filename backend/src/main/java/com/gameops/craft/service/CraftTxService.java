package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.CraftOrder;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.LedgerEntry;
import com.gameops.craft.domain.RecipeVersion;
import com.gameops.craft.repo.HoldRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.repo.RecipeRepository;
import com.gameops.craft.repo.RevokeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * All balance/order state machines run inside transactions with row locks:
 *  - craft preoccupy  : locks recipe header + published version, checks window,
 *                       deducts materials (WHERE qty >= need), writes CONSUME rows + holds
 *  - craft commit     : locks order + holds, CAS PREOCCUPIED->COMMITTED, writes PRODUCE rows
 *  - cancel / timeout : CAS state, credits holds back, writes RELEASE rows (never double)
 *  - operator revoke  : CAS COMMITTED->REVOKED, negative REVOKE rows; shortage -> exception list
 */
@Service
public class CraftTxService {

    private final RecipeRepository recipes;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final HoldRepository holds;
    private final LedgerRepository ledger;
    private final RevokeRepository revokes;
    private final Clock clock;

    public CraftTxService(RecipeRepository recipes, InventoryRepository inventory, OrderRepository orders,
                          HoldRepository holds, LedgerRepository ledger, RevokeRepository revokes, Clock clock) {
        this.recipes = recipes;
        this.inventory = inventory;
        this.orders = orders;
        this.holds = holds;
        this.ledger = ledger;
        this.revokes = revokes;
        this.clock = clock;
    }

    public record PreoccupyResult(CraftOrder order, RecipeVersion version, String orderNo) {}

    /**
     * Step 1 of crafting. Rules are decided HERE, never by button state.
     * Locks order: recipe row + version row, then inventory rows (sorted by item_code).
     */
    @Transactional
    public PreoccupyResult preoccupy(long playerId, long recipeId) {
        Instant now = Instant.now(clock);

        RecipeRepository.RecipeHeader header = recipes.lockHeader(recipeId);
        if (header == null) {
            throw ApiException.notFound("RECIPE_NOT_FOUND", "配方不存在");
        }
        if (!"ACTIVE".equals(header.status())) {
            throw ApiException.conflict("RECIPE_CLOSED", "配方已下架，无法开始合成");
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

        String orderNo = DocNumbers.next("CO", clock);
        Instant deadline = now.plusSeconds(version.craftTimeoutSeconds());
        long orderId = orders.insert(orderNo, playerId, recipeId, version.id(), deadline, now);

        // Deduct atomically row-by-row under row locks. First insufficient line rolls everything back.
        // Sorted item order => all concurrent transactions take inventory locks in the same order.
        for (ItemQty in : inputs) {
            int changed = inventory.deductIfEnough(playerId, in.getItemCode(), in.getQty(), now);
            if (changed == 0) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw ApiException.conflict("MATERIAL_INSUFFICIENT",
                        "材料不足：" + in.getItemCode() + " 需要 " + in.getQty());
            }
            holds.create(orderId, playerId, in.getItemCode(), in.getQty(), now);
            // CONSUME at preoccupy time: the material has left the available balance.
            ledger.insert(orderNo, playerId, in.getItemCode(), "CONSUME", -in.getQty(),
                    null, "POSTED", "合成预占消耗 v" + version.versionNo(), now);
        }

        CraftOrder saved = orders.findByNo(orderNo).orElseThrow();
        return new PreoccupyResult(saved, version, orderNo);
    }

    /** Step 2: finish the craft on the version captured at preoccupy; outputs issued exactly once. */
    @Transactional
    public CraftOrder commit(String orderNo, long playerId) {
        Instant now = Instant.now(clock);
        CraftOrder order = orders.findByNoForUpdate(orderNo)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "合成单不存在"));
        if (order.playerId() != playerId) {
            throw ApiException.forbidden("无权操作他人合成单");
        }
        switch (order.status()) {
            case "COMMITTED" -> {
                return order; // idempotent terminal state; facade decides replay vs conflict
            }
            case "CANCELLED", "TIMEOUT", "REVOKED" ->
                throw ApiException.conflict("ORDER_NOT_OPEN", "合成单已结束：" + order.status());
            default -> { /* PREOCCUPIED */ }
        }
        if (!order.preoccupyDeadline().isAfter(now)) {
            throw ApiException.conflict("PREOCCUPY_EXPIRED", "预占已超时，材料将自动释放");
        }

        // Lock holds so a sweeper/timeout can't free them concurrently with this commit.
        List<HoldRepository.HoldRow> holdRows = holds.lockByOrder(order.id());

        RecipeVersion version = recipes.findVersionById(order.recipeVersionId())
                .orElseThrow(() -> new IllegalStateException("bound version missing"));

        int cas = orders.casCommit(order.id(), now, now);
        if (cas == 0) {
            // Lost the race against timeout: do NOT issue rewards.
            throw ApiException.conflict("ORDER_COMMIT_RACE", "合成单状态已变化，提交失败");
        }
        // Holds were the consumed materials: consumed at preoccupy; now mark them settled.
        for (HoldRepository.HoldRow h : holdRows) {
            holds.markReleased(h.id(), now);
        }
        for (ItemQty out : version.outputs()) {
            inventory.credit(playerId, out.getItemCode(), out.getQty(), now);
            // Unique key (ref_no,player,item,type) makes double PRODUCE impossible.
            ledger.insert(orderNo, playerId, out.getItemCode(), "PRODUCE", out.getQty(),
                    null, "POSTED", "合成产出 v" + version.versionNo(), now);
        }
        return orders.findByNo(orderNo).orElseThrow();
    }

    /** Player cancels before commit; every held line goes back once. */
    @Transactional
    public CraftOrder cancel(String orderNo, long playerId, String reason) {
        Instant now = Instant.now(clock);
        CraftOrder order = orders.findByNoForUpdate(orderNo)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "合成单不存在"));
        if (order.playerId() != playerId) {
            throw ApiException.forbidden("无权操作他人合成单");
        }
        if (!"PREOCCUPIED".equals(order.status())) {
            return order; // already terminal -> nothing to release
        }
        int cas = orders.casCancel(order.id(), reason == null ? "player cancelled" : reason, now);
        if (cas == 0) {
            return orders.findByNo(orderNo).orElseThrow();
        }
        releaseHoldsOnce(order, "CANCEL", now);
        return orders.findByNo(orderNo).orElseThrow();
    }

    /**
     * Called by the timeout sweeper per expired order.
     * CAS guarantees at most one of {commit, cancel, timeout} wins; release is guarded again.
     *
     * @return true if THIS call timed the order out and released holds
     */
    @Transactional
    public boolean timeoutExpiredOrder(String orderNo) {
        Instant now = Instant.now(clock);
        CraftOrder order = orders.findByNoForUpdate(orderNo).orElse(null);
        if (order == null || !"PREOCCUPIED".equals(order.status())) {
            return false;
        }
        int cas = orders.casTimeout(order.id(), now);
        if (cas == 0) {
            return false; // committed/cancelled/already timed out
        }
        releaseHoldsOnce(orders.findByNo(orderNo).orElseThrow(), "TIMEOUT", now);
        return true;
    }

    private void releaseHoldsOnce(CraftOrder order, String trigger, Instant now) {
        List<HoldRepository.HoldRow> rows = holds.lockByOrder(order.id());
        for (HoldRepository.HoldRow h : rows) {
            if (holds.markReleased(h.id(), now) == 1) {
                inventory.credit(h.playerId(), h.itemCode(), h.qty(), now);
                ledger.insert(order.orderNo(), h.playerId(), h.itemCode(), "RELEASE", h.qty(),
                        null, "POSTED", "预占释放（" + trigger + "）", now);
            }
        }
    }

    public record RevokeResult(String revokeNo, String result, List<Map<String, Object>> shortage) {}

    /**
     * Operator clawback of a wrongly rewarded, already-committed craft.
     * Full stock available -> reverse REVOKE rows + deduct.
     * Any output shortfall -> EXCEPTION record + REVOKE_PENDING rows, no balance touched.
     */
    @Transactional
    public RevokeResult revoke(String orderNo, long operatorId) {
        Instant now = Instant.now(clock);
        CraftOrder order = orders.findByNoForUpdate(orderNo)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "合成单不存在"));
        if (!"COMMITTED".equals(order.status())) {
            throw ApiException.conflict("ORDER_NOT_REVOKABLE",
                    "仅已完成的合成可撤销，当前状态：" + order.status());
        }
        List<LedgerEntry> produces = ledger.findProducesByOrderNo(orderNo);
        if (produces.isEmpty()) {
            throw ApiException.conflict("ORDER_NO_OUTPUT", "合成单无产出流水，无法撤销");
        }

        String revokeNo = DocNumbers.next("RV", clock);

        // Check every output balance under lock (sorted, same lock order as crafting).
        List<LedgerEntry> sorted = produces.stream()
                .sorted(Comparator.comparing(LedgerEntry::itemCode)).toList();
        List<Map<String, Object>> shortage = new ArrayList<>();
        boolean complete = true;
        for (LedgerEntry p : sorted) {
            long have = inventory.lockAndGet(p.playerId(), p.itemCode());
            long need = p.qtyDelta();
            if (have < need) {
                complete = false;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("itemCode", p.itemCode());
                s.put("need", need);
                s.put("have", have);
                s.put("short", need - have);
                shortage.add(s);
            }
        }

        if (complete) {
            // Locked above; deduct is safe. Negative ledger rows are the reversal trail.
            for (LedgerEntry p : sorted) {
                int changed = inventory.deductIfEnough(p.playerId(), p.itemCode(), p.qtyDelta(), now);
                if (changed == 0) {
                    throw new IllegalStateException("clawback deduction failed after lock: " + p.itemCode());
                }
                ledger.insert(revokeNo, p.playerId(), p.itemCode(), "REVOKE", -p.qtyDelta(),
                        orderNo, "POSTED", "运营撤销错误奖励，冲销原产出", now);
            }
            int cas = orders.casRevoke(order.id(), revokeNo, now);
            if (cas == 0) {
                throw ApiException.conflict("ORDER_REVOKE_RACE", "合成单状态已变化");
            }
            revokes.insert(revokeNo, order.id(), orderNo, order.playerId(), "REVERSED", null, operatorId, now);
            return new RevokeResult(revokeNo, "REVERSED", List.of());
        }

        // Materials/reward already used elsewhere: into exception list; nothing deducted.
        String shortageJson;
        try {
            shortageJson = Json.MAPPER.writeValueAsString(shortage);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (LedgerEntry p : sorted) {
            ledger.insert(revokeNo, p.playerId(), p.itemCode(), "REVOKE_PENDING", -p.qtyDelta(),
                    orderNo, "PENDING", "撤销挂账：材料已使用，等待人工处理", now);
        }
        revokes.insert(revokeNo, order.id(), orderNo, order.playerId(), "EXCEPTION",
                shortageJson, operatorId, now);
        return new RevokeResult(revokeNo, "EXCEPTION", shortage);
    }

    // ---- helpers -----------------------------------------------------------

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

    public static List<Map<String, Object>> parseShortage(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
