package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.domain.CraftOrder;
import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.domain.CraftPlanUnit;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.LedgerEntry;
import com.gameops.craft.repo.IdempotencyRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.repo.PlanRepairRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.gameops.craft.common.Json;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciliation and operator repair for batch plans.
 *
 * Reconciliation is a read-only cross-check of the three durable layers:
 * plan_unit state, craft_order state and ledger_entry rows. It never mutates.
 *
 * Repair is append-only: the only allowed action is posting a NEW signed
 * COMPENSATE ledger line (and the matching balance movement). Historical ledger
 * rows are never updated or deleted. A repair request carries an Idempotency-Key
 * persisted in craft_plan_repair (unique), so the identical repair can be
 * retried any number of times and posts at most one compensation.
 */
@Service
public class PlanReconcileService {

    private static final Logger log = LoggerFactory.getLogger(PlanReconcileService.class);

    public static final String TYPE_PRODUCE_MISSING = "PRODUCE_MISSING";
    public static final String TYPE_CONSUME_MISSING = "CONSUME_MISSING";
    public static final String TYPE_PRODUCE_EXTRA = "PRODUCE_EXTRA";
    public static final String TYPE_ORDER_MISSING = "ORDER_MISSING";
    public static final String TYPE_ORDER_STATUS_MISMATCH = "ORDER_STATUS_MISMATCH";
    public static final String TYPE_STUCK_LEASE = "STUCK_LEASE";
    public static final String TYPE_PLAN_STATUS_MISMATCH = "PLAN_STATUS_MISMATCH";

    private final PlanRepository plans;
    private final PlanUnitRepository units;
    private final OrderRepository orders;
    private final LedgerRepository ledger;
    private final InventoryRepository inventory;
    private final PlanRepairRepository repairs;
    private final IdempotencyRepository idem;
    private final Clock clock;
    private final long stuckLeaseSeconds;
    private final long inFlightTtlSeconds;

    public PlanReconcileService(PlanRepository plans, PlanUnitRepository units,
                                OrderRepository orders, LedgerRepository ledger,
                                InventoryRepository inventory, PlanRepairRepository repairs,
                                IdempotencyRepository idem, Clock clock,
                                @Value("${app.plan.stuck-lease-seconds:300}") long stuckLeaseSeconds,
                                @Value("${app.in-flight-ttl-seconds:300}") long inFlightTtlSeconds) {
        this.plans = plans;
        this.units = units;
        this.orders = orders;
        this.ledger = ledger;
        this.inventory = inventory;
        this.repairs = repairs;
        this.idem = idem;
        this.clock = clock;
        this.stuckLeaseSeconds = stuckLeaseSeconds;
        this.inFlightTtlSeconds = inFlightTtlSeconds;
    }

    public record Diff(String planNo, int unitNo, String type, String severity,
                       String itemCode, Long expected, Long actual, String detail) {}

    // ---- read-only reconciliation -----------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> reconcilePlan(String planNo) {
        CraftPlan plan = plans.findByNo(planNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        List<ItemQty> expectedInputs = PlanUnitTxService.parseItems(plan.inputsJson());
        List<ItemQty> expectedOutputs = PlanUnitTxService.parseItems(plan.outputsJson());

        List<Diff> diffs = new ArrayList<>();
        int done = 0, skipped = 0, failed = 0, leased = 0, pending = 0;
        for (CraftPlanUnit u : units.listByPlan(plan.id())) {
            switch (u.status()) {
                case "DONE" -> done++;
                case "SKIPPED" -> skipped++;
                case "FAILED" -> failed++;
                case "LEASED" -> leased++;
                default -> pending++;
            }
            diffs.addAll(checkUnit(plan, u, expectedInputs, expectedOutputs));
        }

        // Plan status vs. its units.
        boolean hasUnfinished = pending > 0 || leased > 0;
        if (!hasUnfinished) {
            String expectedStatus = expectedTerminalStatus(plan, done, skipped, failed);
            if (!expectedStatus.equals(plan.status())) {
                diffs.add(new Diff(planNo, 0, TYPE_PLAN_STATUS_MISMATCH, "WARN",
                        null, null, null,
                        "计划状态 " + plan.status() + " 与序号统计不一致，应为 " + expectedStatus));
            }
        } else if (plan.isTerminal()) {
            diffs.add(new Diff(planNo, 0, TYPE_PLAN_STATUS_MISMATCH, "WARN",
                    null, null, null,
                    "计划已终态 " + plan.status() + " 但仍有 "
                            + (pending + leased) + " 个未完成序号"));
        }

        Map<String, Object> body = PlanService.planBody(plan);
        body.put("counts", Map.of("done", done, "skipped", skipped, "failed", failed,
                "leased", leased, "pending", pending));
        body.put("diffs", diffs.stream().map(PlanReconcileService::diffBody).toList());
        body.put("consistent", diffs.isEmpty());
        return body;
    }

    private List<Diff> checkUnit(CraftPlan plan, CraftPlanUnit u,
                                 List<ItemQty> expectedInputs, List<ItemQty> expectedOutputs) {
        List<Diff> diffs = new ArrayList<>();
        Instant now = Instant.now(clock);

        // Stuck lease: LEASED well past expiry with no active worker.
        if ("LEASED".equals(u.status())
                && u.leaseExpiresAt() != null
                && u.leaseExpiresAt().plusSeconds(stuckLeaseSeconds).isBefore(now)) {
            diffs.add(new Diff(plan.planNo(), u.unitNo(), TYPE_STUCK_LEASE, "WARN",
                    null, null, null,
                    "序号租约长时间未推进（owner=" + u.leaseOwner()
                            + "，过期于 " + u.leaseExpiresAt() + "）；可等待 worker 恢复或人工修复"));
        }

        // DONE units must have a committed order with exactly the snapshot outputs.
        if ("DONE".equals(u.status())) {
            if (u.orderNo() == null) {
                diffs.add(new Diff(plan.planNo(), u.unitNo(), TYPE_ORDER_MISSING, "ERROR",
                        null, (long) expectedOutputs.size(), 0L,
                        "序号标记 DONE 但缺少合成单号"));
                return diffs;
            }
            CraftOrder order = orders.findByNo(u.orderNo()).orElse(null);
            if (order == null) {
                diffs.add(new Diff(plan.planNo(), u.unitNo(), TYPE_ORDER_MISSING, "ERROR",
                        null, null, null, "合成单不存在：" + u.orderNo()));
                return diffs;
            }
            if (!"COMMITTED".equals(order.status()) && !"REVOKED".equals(order.status())) {
                diffs.add(new Diff(plan.planNo(), u.unitNo(), TYPE_ORDER_STATUS_MISMATCH, "ERROR",
                        null, null, null, "DONE 序号的合成单状态为 " + order.status()));
            }
            diffs.addAll(checkLedgerQuantities(plan, u, expectedOutputs, "PRODUCE",
                    TYPE_PRODUCE_MISSING, TYPE_PRODUCE_EXTRA, true));
        }

        // SKIPPED / PENDING units must never have touched materials.
        if (("SKIPPED".equals(u.status()) || "PENDING".equals(u.status())) && u.orderNo() != null) {
            diffs.add(new Diff(plan.planNo(), u.unitNo(), TYPE_ORDER_STATUS_MISMATCH, "WARN",
                    null, null, null,
                    u.status() + " 序号已绑定合成单 " + u.orderNo()));
        }
        return diffs;
    }

    /**
     * Compare posted PRODUCE (or CONSUME) lines of a unit's order against the
     * frozen snapshot. A previously posted COMPENSATE line for the same item and
     * direction counts toward the repaired side, so a reconciled unit stays
     * consistent (the historical missing line is intentionally not rewritten).
     */
    private List<Diff> checkLedgerQuantities(CraftPlan plan, CraftPlanUnit u,
                                             List<ItemQty> expectedLines, String entryType,
                                             String missingType, String extraType,
                                             boolean positiveSide) {
        List<Diff> diffs = new ArrayList<>();
        List<LedgerEntry> lines = ledger.listByRef(u.orderNo());
        for (ItemQty expected : expectedLines) {
            long postedType = sum(lines, entryType, expected.getItemCode());
            long postedComp = sumCompensation(lines, expected.getItemCode(), positiveSide);
            long actual = postedType + postedComp;
            if (actual < expected.getQty()) {
                long missing = expected.getQty() - actual;
                diffs.add(new Diff(plan.planNo(), u.unitNo(), missingType, "ERROR",
                        expected.getItemCode(), expected.getQty(), actual,
                        "缺少 " + missing + " 个 " + expected.getItemCode()
                                + "（应有 " + expected.getQty() + "，实发 " + actual + "）"));
            } else if (actual > expected.getQty() && extraType != null) {
                diffs.add(new Diff(plan.planNo(), u.unitNo(), extraType, "WARN",
                        expected.getItemCode(), expected.getQty(), actual,
                        "多发 " + (actual - expected.getQty()) + " 个 " + expected.getItemCode()));
            }
        }
        return diffs;
    }

    private static long sum(List<LedgerEntry> lines, String type, String item) {
        return lines.stream()
                .filter(l -> type.equals(l.entryType()) && item.equals(l.itemCode()))
                .mapToLong(LedgerEntry::qtyDelta).sum();
    }

    /** Compensation appended for this item in the required direction counts once. */
    private long sumCompensation(List<LedgerEntry> lines, String item, boolean positiveSide) {
        return lines.stream()
                .filter(l -> "COMPENSATE".equals(l.entryType()) && item.equals(l.itemCode()))
                .mapToLong(LedgerEntry::qtyDelta)
                .filter(d -> positiveSide ? d > 0 : d < 0)
                .map(Math::abs)
                .sum();
    }

    private String expectedTerminalStatus(CraftPlan plan, int done, int skipped, int failed) {
        if ("CANCELLED".equals(plan.status())) {
            return "CANCELLED";
        }
        if (done == plan.totalCount()) {
            return "COMPLETED";
        }
        if (done == 0 && failed > 0) {
            return "FAILED";
        }
        return "PARTIAL";
    }

    // ---- append-only repair ------------------------------------------------

    public record RepairRequest(String planNo, Integer unitNo, String diffType,
                                String itemCode, Long qty, String remark) {}

    /**
     * Apply ONE repair as a new COMPENSATE ledger line.
     *
     * qty is the SIGNED compensation to post:
     *   PRODUCE_MISSING -> positive (issue the missing reward),
     *   CONSUME_MISSING -> negative (recover the un-deducted material).
     * The Idempotency-Key is reserved like other write endpoints; a repeated
     * request with the same key replays the original result and never posts a
     * second compensation. For negative compensations the balance is checked and
     * conditionally deducted; insufficient stock fails with 409 and posts nothing.
     */
    public Map<String, Object> repair(long operatorId, RepairRequest req, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Instant now = Instant.now(clock);
        if (!idem.tryReserve(key, operatorId, "PLAN_REPAIR", now)) {
            return replayRepair(key);
        }
        try {
            Map<String, Object> result = repairInTx(operatorId, req, key, now);
            String compRefNo = (String) result.get("compRefNo");
            idem.complete(key, compRefNo, Json.MAPPER.writeValueAsString(result), Instant.now(clock));
            return result;
        } catch (ApiException e) {
            idem.delete(key);
            throw e;
        } catch (Exception e) {
            log.error("repair failed for {}#{}", req.planNo(), req.unitNo(), e);
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public Map<String, Object> repairInTx(long operatorId, RepairRequest req, String repairKey,
                                          Instant now) {
        CraftPlan plan = plans.findByNoForUpdate(req.planNo())
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        validateRepair(req);

        long delta = req.qty();
        String compRefNo = DocNumbers.next("CP", clock);
        String remark = "运营对账补偿 " + req.diffType()
                + (req.remark() == null || req.remark().isBlank() ? "" : "：" + req.remark());

        // Balance side, exactly once with the ledger insert in this same tx.
        if (delta > 0) {
            inventory.credit(plan.playerId(), req.itemCode(), delta, now);
        } else {
            int changed = inventory.deductIfEnough(plan.playerId(), req.itemCode(),
                    Math.abs(delta), now);
            if (changed == 0) {
                throw ApiException.conflict("COMPENSATE_INSUFFICIENT_BALANCE",
                        "补偿扣回时余额不足：" + req.itemCode() + " 需要 " + Math.abs(delta)
                                + "；请先补足或调整修复数量，历史流水未做任何修改");
            }
        }
        ledger.insert(compRefNo, plan.playerId(), req.itemCode(), "COMPENSATE", delta,
                req.unitNo() == null ? null : unitOrderNo(plan, req.unitNo()),
                plan.planNo(), req.unitNo(), "POSTED", remark, now);

        // Unique(repair_key) is the durable retry anchor. A duplicate here means a
        // concurrent identical repair slipped past the idempotency reservation;
        // roll the whole tx back so no second COMPENSATE row is posted.
        boolean inserted = repairs.tryInsert(repairKey, plan.planNo(), req.unitNo(),
                req.diffType(), req.itemCode(), delta, compRefNo, operatorId,
                req.remark(), now);
        if (!inserted) {
            throw ApiException.conflict("REPAIR_DUPLICATE", "相同修复请求已存在，请用同键回放");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planNo", plan.planNo());
        body.put("unitNo", req.unitNo());
        body.put("diffType", req.diffType());
        body.put("itemCode", req.itemCode());
        body.put("qtyDelta", delta);
        body.put("compRefNo", compRefNo);
        body.put("replayed", false);
        return body;
    }

    private void validateRepair(RepairRequest req) {
        if (req.planNo() == null || req.planNo().isBlank()) {
            throw ApiException.badRequest("REPAIR_PLAN_REQUIRED", "必须指定计划号");
        }
        if (req.itemCode() == null || req.itemCode().isBlank()) {
            throw ApiException.badRequest("REPAIR_ITEM_REQUIRED", "必须指定道具编码");
        }
        if (req.qty() == null || req.qty() == 0) {
            throw ApiException.badRequest("REPAIR_QTY_REQUIRED", "补偿数量必须为非零带符号整数");
        }
        if (!List.of(TYPE_PRODUCE_MISSING, TYPE_CONSUME_MISSING, TYPE_PRODUCE_EXTRA)
                .contains(req.diffType())) {
            throw ApiException.badRequest("REPAIR_TYPE_UNSUPPORTED",
                    "仅支持补偿 PRODUCE_MISSING / CONSUME_MISSING / PRODUCE_EXTRA");
        }
        if (TYPE_PRODUCE_MISSING.equals(req.diffType()) && req.qty() <= 0) {
            throw ApiException.badRequest("REPAIR_SIGN_INVALID", "补发产出数量必须为正数");
        }
        if (TYPE_CONSUME_MISSING.equals(req.diffType()) && req.qty() >= 0) {
            throw ApiException.badRequest("REPAIR_SIGN_INVALID", "补扣材料数量必须为负数");
        }
    }

    private String unitOrderNo(CraftPlan plan, int unitNo) {
        return units.listByPlan(plan.id()).stream()
                .filter(u -> u.unitNo() == unitNo)
                .map(CraftPlanUnit::orderNo)
                .findFirst().orElse(null);
    }

    private Map<String, Object> replayRepair(String key) {
        var idemRow = idem.find(key)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE", "请求处理中，请重试"));
        if ("DONE".equals(idemRow.status())) {
            try {
                JsonNode node = Json.MAPPER.readTree(idemRow.responseJson());
                @SuppressWarnings("unchecked")
                Map<String, Object> replay = Json.MAPPER.convertValue(node, Map.class);
                replay.put("replayed", true);
                return replay;
            } catch (Exception e) {
                throw new IllegalStateException("bad stored repair response", e);
            }
        }
        if (idemRow.updatedAt().isBefore(Instant.now(clock).minus(Duration.ofSeconds(inFlightTtlSeconds)))) {
            idem.delete(key);
            throw ApiException.conflict("IDEMPOTENCY_STALE", "上次修复请求中断，请重新发起");
        }
        throw ApiException.conflict("RETRY_IN_FLIGHT", "修复请求正在处理，请勿重复提交");
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "修复请求必须携带 Idempotency-Key");
        }
        if (key.length() > 80) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_TOO_LONG", "幂等键长度不能超过 80");
        }
        return key;
    }

    public List<Map<String, Object>> listRepairs(String planNo, int limit) {
        List<PlanRepairRepository.RepairRow> rows = planNo == null || planNo.isBlank()
                ? repairs.listRecent(limit)
                : repairs.listByPlan(planNo);
        return rows.stream().map(PlanReconcileService::repairBody).toList();
    }

    static Map<String, Object> repairBody(PlanRepairRepository.RepairRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repairKey", r.repairKey());
        m.put("planNo", r.planNo());
        m.put("unitNo", r.unitNo());
        m.put("diffType", r.diffType());
        m.put("itemCode", r.itemCode());
        m.put("qtyDelta", r.qtyDelta());
        m.put("compRefNo", r.compRefNo());
        m.put("operatorId", r.operatorId());
        m.put("remark", r.remark());
        m.put("createdAt", r.createdAt());
        return m;
    }

    static Map<String, Object> diffBody(Diff d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planNo", d.planNo());
        m.put("unitNo", d.unitNo());
        m.put("type", d.type());
        m.put("severity", d.severity());
        m.put("itemCode", d.itemCode());
        m.put("expected", d.expected());
        m.put("actual", d.actual());
        m.put("detail", d.detail());
        return m;
    }
}
