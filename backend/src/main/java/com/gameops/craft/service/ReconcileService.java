package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.BatchPlan;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.LedgerEntry;
import com.gameops.craft.domain.PlanUnit;
import com.gameops.craft.repo.BatchPlanRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.repo.PlanUnitRepository;
import com.gameops.craft.repo.RepairRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation + operator repair for batch plans.
 *
 * A reconcile is READ ONLY and reports, per plan/unit, where the three sides disagree:
 * plan/unit status, craft_order status, inventory effect, and ledger rows.
 *
 * Repair never rewrites or deletes history: a missing side is completed ONLY by APPENDING a
 * compensating ledger entry (entry_type COMPENSATE, the original unit's plan_no/unit_no kept)
 * together with the matching balance movement. The repair Idempotency-Key is a unique key, so
 * the exact same repair request retried any number of times creates at most one compensation.
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final BatchPlanRepository plans;
    private final PlanUnitRepository units;
    private final OrderRepository orders;
    private final LedgerRepository ledger;
    private final InventoryRepository inventory;
    private final RepairRepository repairs;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public ReconcileService(BatchPlanRepository plans, PlanUnitRepository units, OrderRepository orders,
                            LedgerRepository ledger, InventoryRepository inventory,
                            RepairRepository repairs, Clock clock, TransactionTemplate txTemplate) {
        this.plans = plans;
        this.units = units;
        this.orders = orders;
        this.ledger = ledger;
        this.inventory = inventory;
        this.repairs = repairs;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    public record Issue(String issueType, String severity, String planNo, Integer unitNo,
                        String orderNo, String itemCode, Long expected, Long actual, String detail) {}

    // ---- read-only reconciliation ------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> reconcilePlan(String planNo) {
        BatchPlan plan = plans.findByNo(planNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        List<PlanUnit> unitRows = units.listByPlan(plan.id());
        List<LedgerEntry> entries = ledger.listByPlan(planNo);
        List<ItemQty> outputs = PlanTxService.parseItems(plan.outputsJson());
        List<ItemQty> inputs = PlanTxService.parseItems(plan.inputsJson());

        List<Issue> issues = new ArrayList<>();
        Map<Integer, List<LedgerEntry>> byUnit = new LinkedHashMap<>();
        for (LedgerEntry e : entries) {
            byUnit.computeIfAbsent(e.unitNo(), k -> new ArrayList<>()).add(e);
        }

        int done = 0, skipped = 0, failed = 0, open = 0;
        for (PlanUnit u : unitRows) {
            List<LedgerEntry> rows = byUnit.getOrDefault(u.unitNo(), List.of());
            switch (u.status()) {
                case "DONE" -> {
                    done++;
                    checkDoneUnit(plan, u, rows, outputs, issues);
                }
                case "SKIPPED" -> {
                    skipped++;
                    checkSkippedUnit(u, rows, issues);
                }
                case "FAILED" -> failed++;
                default -> open++;
            }
            if (u.orderNo() == null && !"PENDING".equals(u.status()) && !"SKIPPED".equals(u.status())) {
                issues.add(new Issue("UNIT_ORDER_MISSING", "ERROR", planNo, u.unitNo(), null,
                        null, null, null, "非初始状态的序号缺少合成单"));
            }
        }

        // Plan-level status vs unit reality.
        int settled = done + skipped + failed;
        if (settled == plan.totalUnits() && !plan.isTerminal()) {
            issues.add(new Issue("PLAN_STATUS_MISMATCH", "ERROR", planNo, null, null, null,
                    (long) plan.totalUnits(), (long) settled,
                    "所有序号已结束但计划仍为 " + plan.status()));
        }
        if (plan.isTerminal() && open > 0) {
            issues.add(new Issue("PLAN_STATUS_MISMATCH", "ERROR", planNo, null, null, null,
                    0L, (long) open, "计划已终态但仍有未结束序号"));
        }
        if (plan.completedCount() != done || plan.skippedCount() != skipped || plan.failedCount() != failed) {
            issues.add(new Issue("PLAN_COUNTS_MISMATCH", "WARN", planNo, null, null, null,
                    null, null,
                    "计划计数 completed=%d/failed=%d/skipped=%d 与序号实际 %d/%d/%d 不一致"
                            .formatted(plan.completedCount(), plan.failedCount(), plan.skippedCount(),
                                    done, failed, skipped)));
        }

        // Net material/output effect: per item, DONE units must account for -N*input / +N*output
        // in POSTED ledger rows tied to this plan.
        Map<String, Long> net = new LinkedHashMap<>();
        for (LedgerEntry e : entries) {
            if ("POSTED".equals(e.status())) {
                net.merge(e.itemCode(), e.qtyDelta(), Long::sum);
            }
        }
        for (ItemQty in : inputs) {
            long expected = -(long) in.getQty() * done;
            long actual = net.getOrDefault(in.getItemCode(), 0L);
            if (actual != expected) {
                issues.add(new Issue("LEDGER_NET_MISMATCH", "ERROR", planNo, null, null,
                        in.getItemCode(), expected, actual, "材料净变动与完成序号不符"));
            }
        }
        for (ItemQty out : outputs) {
            long expected = (long) out.getQty() * done;
            long actual = net.getOrDefault(out.getItemCode(), 0L);
            if (actual != expected) {
                issues.add(new Issue("LEDGER_NET_MISMATCH", "ERROR", planNo, null, null,
                        out.getItemCode(), expected, actual, "产出净变动与完成序号不符"));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", PlanService.planBody(plan));
        body.put("issues", issues);
        body.put("consistent", issues.isEmpty());
        body.put("ledgerCount", entries.size());
        return body;
    }

    private void checkDoneUnit(BatchPlan plan, PlanUnit u, List<LedgerEntry> rows,
                               List<ItemQty> outputs, List<Issue> issues) {
        // craft_order must be COMMITTED.
        if (u.orderNo() != null) {
            orders.findByNo(u.orderNo()).ifPresentOrElse(o -> {
                if (!"COMMITTED".equals(o.status())) {
                    issues.add(new Issue("ORDER_STATUS_MISMATCH", "ERROR", plan.planNo(), u.unitNo(),
                            u.orderNo(), null, null, null,
                            "序号 DONE 但合成单状态为 " + o.status()));
                }
            }, () -> issues.add(new Issue("ORDER_MISSING", "ERROR", plan.planNo(), u.unitNo(),
                    u.orderNo(), null, null, null, "合成单不存在")));
        }
        // Every snapshot output must have exactly one POSTED PRODUCE row.
        for (ItemQty out : outputs) {
            long produce = rows.stream()
                    .filter(e -> "PRODUCE".equals(e.entryType()) && out.getItemCode().equals(e.itemCode())
                            && "POSTED".equals(e.status()))
                    .mapToLong(LedgerEntry::qtyDelta).sum();
            if (produce == 0L) {
                issues.add(new Issue("MISSING_PRODUCE", "ERROR", plan.planNo(), u.unitNo(), u.orderNo(),
                        out.getItemCode(), out.getQty(), 0L, "已完成序号缺少产出发放"));
            } else if (produce != out.getQty()) {
                issues.add(new Issue("PRODUCE_QTY_MISMATCH", "ERROR", plan.planNo(), u.unitNo(),
                        u.orderNo(), out.getItemCode(), out.getQty(), produce, "产出数量与快照不符"));
            }
        }
    }

    private void checkSkippedUnit(PlanUnit u, List<LedgerEntry> rows, List<Issue> issues) {
        long consume = rows.stream().filter(e -> "CONSUME".equals(e.entryType())).count();
        long produce = rows.stream().filter(e -> "PRODUCE".equals(e.entryType())).count();
        if (consume != 0 || produce != 0) {
            issues.add(new Issue("SKIPPED_WITH_MOVEMENT", "ERROR", u.planNo(), u.unitNo(), u.orderNo(),
                    null, null, null, "已跳过序号却存在扣料/发奖流水"));
        }
    }

    /** Lightweight scan across all plans: only the inconsistent ones, for the operator queue. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> scanAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BatchPlan p : plans.listRecent(200)) {
            Map<String, Object> r = reconcilePlan(p.planNo());
            @SuppressWarnings("unchecked")
            List<Issue> issues = (List<Issue>) r.get("issues");
            if (!issues.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("planNo", p.planNo());
                row.put("status", p.status());
                row.put("totalUnits", p.totalUnits());
                row.put("completedCount", p.completedCount());
                row.put("issueCount", issues.size());
                row.put("issues", issues);
                out.add(row);
            }
        }
        // Also surface plans stuck in stop state without a terminal status.
        for (BatchPlan p : plans.listStuck(200)) {
            boolean present = out.stream().anyMatch(m -> p.planNo().equals(m.get("planNo")));
            if (!present) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("planNo", p.planNo());
                row.put("status", p.status());
                row.put("totalUnits", p.totalUnits());
                row.put("completedCount", p.completedCount());
                row.put("issueCount", 1);
                row.put("issues", List.of(new Issue("PLAN_STUCK", "WARN", p.planNo(), null, null,
                        null, null, null, "计划已停止但未进入终态")));
                out.add(row);
            }
        }
        return out;
    }

    // ---- operator repair (append-only compensation) ------------------------

    public record RepairRequest(String idempotencyKey, String planNo, Integer unitNo,
                                String issueType, String itemCode) {}

    public record RepairResult(String repairNo, String result, String issueType,
                               String planNo, Integer unitNo, String compensationRefNo,
                               List<Map<String, Object>> movements, boolean replayed) {}

    /**
     * Apply one repair, safe to run concurrently and safe to retry with the same key.
     *
     * Phase 1 (its own tx) reserves the repair idempotency key (unique constraint). Exactly one
     *           concurrent caller wins; losers replay the winner's stored result.
     * Phase 2 (its own tx) appends the compensation. Business unique keys (one PRODUCE per
     *           order/item, one COMPENSATE per repairNo) keep it idempotent; if the process died
     *           after phase 1, a retried call finds the PENDING reservation and resumes phase 2.
     */
    public RepairResult repair(RepairRequest req, long operatorId) {
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "修复请求必须携带 Idempotency-Key");
        }
        var existing = repairs.findByIdemKey(req.idempotencyKey());
        if (existing.isPresent() && ("APPLIED".equals(existing.get().result())
                || "NOOP".equals(existing.get().result()))) {
            return toResult(existing.get(), true);
        }

        final BatchPlan plan = plans.findByNo(req.planNo())
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "批量计划不存在"));
        final PlanUnit unit;
        if (req.unitNo() != null) {
            int un = req.unitNo();
            unit = units.listByPlan(plan.id()).stream().filter(u -> u.unitNo() == un).findFirst()
                    .orElseThrow(() -> ApiException.notFound("UNIT_NOT_FOUND", "序号不存在"));
        } else {
            unit = null;
        }
        final String issueType = req.issueType() == null ? "" : req.issueType();

        final String repairNo;
        if (existing.isPresent()) {
            // Resume after a crash between reservation and compensation.
            repairNo = existing.get().repairNo();
        } else {
            repairNo = DocNumbers.next("RP", clock);
            RepairRepository.RepairRow winner = txTemplate.execute(s ->
                    reserveRepair(req, plan, unit, issueType, repairNo, operatorId));
            if (winner != null) {
                return toResult(winner, true);
            }
        }
        return txTemplate.execute(s ->
                applyRepair(req.idempotencyKey(), plan, unit, issueType, req.itemCode(), repairNo));
    }

    /** Phase 1: INSERT the key reservation; on duplicate race return the winner (else null). */
    private RepairRepository.RepairRow reserveRepair(RepairRequest req, BatchPlan plan, PlanUnit unit,
                                                     String issueType, String repairNo, long operatorId) {
        Instant now = Instant.now(clock);
        boolean won = repairs.tryInsert(repairNo, req.idempotencyKey(), plan.planNo(),
                req.unitNo(), unit == null ? null : unit.orderNo(), issueType, "PENDING",
                null, operatorId, now);
        if (won) {
            return null;
        }
        return repairs.findByIdemKey(req.idempotencyKey())
                .orElseThrow(() -> ApiException.conflict("REPAIR_RACE", "修复请求冲突，请重试"));
    }

    /** Phase 2: append the compensation once, then mark the reservation DONE. */
    private RepairResult applyRepair(String key, BatchPlan plan, PlanUnit unit, String issueType,
                                     String itemCode, String repairNo) {
        // At most one resume of a PENDING reservation performs the compensation.
        RepairRepository.RepairRow reserved = repairs.findByRepairNo(repairNo).orElseThrow();
        if ("PENDING".equals(reserved.result())) {
            if (repairs.claimForApply(repairNo) == 0) {
                // Another process just claimed it; replay whatever it records.
                RepairRepository.RepairRow other = repairs.findByIdemKey(key).orElseThrow();
                return toResult(other, true);
            }
        } else if ("APPLIED".equals(reserved.result()) || "NOOP".equals(reserved.result())) {
            return toResult(reserved, true);
        } // APPLYING -> we own it (this very tx) and continue.

        Instant now = Instant.now(clock);
        List<Map<String, Object>> movements = new ArrayList<>();
        String result = "APPLIED";
        switch (issueType) {
            case "MISSING_PRODUCE" -> movements = repairMissingProduce(plan, unit, itemCode, repairNo, now);
            case "MISSING_CONSUME" -> movements = repairMissingConsume(plan, unit, itemCode, repairNo, now);
            case "PLAN_STATUS_MISMATCH", "PLAN_STUCK" -> repairPlanStatus(plan, now);
            default -> throw ApiException.badRequest("UNSUPPORTED_ISSUE",
                    "暂不支持的修复类型：" + issueType);
        }
        if (movements.isEmpty() && !issueType.startsWith("PLAN_")) {
            result = "NOOP"; // re-check showed nothing was actually missing
        }
        String detail = writeJson(Map.of("movements", movements, "issueType", issueType));
        repairs.complete(repairNo, result, detail, now);
        RepairRepository.RepairRow row = repairs.findByIdemKey(key).orElseThrow();
        return toResult(row, false);
    }

    /** Append the missing PRODUCE side: credit inventory once + one COMPENSATE ledger row. */
    private List<Map<String, Object>> repairMissingProduce(BatchPlan plan, PlanUnit unit,
                                                           String itemCode, String repairNo, Instant now) {
        if (unit == null || !"DONE".equals(unit.status())) {
            throw ApiException.conflict("REPAIR_UNIT_NOT_DONE",
                    "仅 DONE 序号可补发产出，当前：" + (unit == null ? "null" : unit.status()));
        }
        ItemQty out = PlanTxService.parseItems(plan.outputsJson()).stream()
                .filter(i -> i.getItemCode().equals(itemCode)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("ITEM_NOT_IN_OUTPUT", "快照产出中无此道具"));
        boolean exists = ledger.exists(unit.orderNo(), unit.playerId(), itemCode, "PRODUCE");
        if (exists) {
            return List.of(); // already fixed by the real flow or a prior compensation
        }
        // The anti-double anchor for the missing side is the unit order's PRODUCE unique key;
        // COMPENSATE itself is additionally unique on (ref_no=repairNo, player, item, type).
        inventory.credit(unit.playerId(), itemCode, out.getQty(), now);
        ledger.insert(unit.orderNo(), unit.playerId(), itemCode, "PRODUCE", out.getQty(),
                null, "POSTED",
                "对账补偿发放 planNo=" + plan.planNo() + " unitNo=" + unit.unitNo() + " repair=" + repairNo,
                now, plan.planNo(), unit.unitNo());
        ledger.insert(repairNo, unit.playerId(), itemCode, "COMPENSATE", out.getQty(),
                unit.orderNo(), "POSTED",
                "补偿流水：补发缺失产出，原历史流水不变",
                now, plan.planNo(), unit.unitNo());
        orders.findByNo(unit.orderNo()).ifPresent(o -> {
            if (!"COMMITTED".equals(o.status())) {
                orders.casCommitBatchUnit(o.id(), now);
            }
        });
        return List.of(movement(itemCode, out.getQty(), unit.orderNo(), "PRODUCE+COMPENSATE"));
    }

    /**
     * Append the missing CONSUME side: deduct once (guarded by qty>=0) + one negative
     * COMPENSATE row. Used when an output exists without its material consumption.
     */
    private List<Map<String, Object>> repairMissingConsume(BatchPlan plan, PlanUnit unit,
                                                           String itemCode, String repairNo, Instant now) {
        if (unit == null || unit.orderNo() == null) {
            throw ApiException.badRequest("UNIT_REQUIRED", "补扣材料需要指定序号");
        }
        ItemQty in = PlanTxService.parseItems(plan.inputsJson()).stream()
                .filter(i -> i.getItemCode().equals(itemCode)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("ITEM_NOT_IN_INPUT", "快照材料中无此道具"));
        if (ledger.exists(unit.orderNo(), unit.playerId(), itemCode, "CONSUME")) {
            return List.of();
        }
        int changed = inventory.deductIfEnough(unit.playerId(), itemCode, in.getQty(), now);
        if (changed == 0) {
            throw ApiException.conflict("COMPENSATE_INSUFFICIENT",
                    "补扣失败：背包 " + itemCode + " 不足 " + in.getQty());
        }
        ledger.insert(unit.orderNo(), unit.playerId(), itemCode, "CONSUME", -in.getQty(),
                null, "POSTED",
                "对账补扣 planNo=" + plan.planNo() + " unitNo=" + unit.unitNo() + " repair=" + repairNo,
                now, plan.planNo(), unit.unitNo());
        ledger.insert(repairNo, unit.playerId(), itemCode, "COMPENSATE", -in.getQty(),
                unit.orderNo(), "POSTED", "补偿流水：补扣缺失材料，原历史流水不变",
                now, plan.planNo(), unit.unitNo());
        return List.of(movement(itemCode, -in.getQty(), unit.orderNo(), "CONSUME+COMPENSATE"));
    }

    /** Status side is repaired by CAS only; no ledger or balance change. */
    private void repairPlanStatus(BatchPlan plan, Instant now) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlanUnitRepository.StatusCount sc : units.countByStatus(plan.id())) {
            counts.put(sc.status(), sc.count());
        }
        int done = counts.getOrDefault("DONE", 0);
        int failed = counts.getOrDefault("FAILED", 0);
        int skipped = counts.getOrDefault("SKIPPED", 0);
        int open = plan.totalUnits() - done - failed - skipped;
        if (open > 0) {
            throw ApiException.conflict("PLAN_NOT_SETTLED",
                    "仍有 " + open + " 个序号未结束，不能修复计划终态");
        }
        String terminal = done == plan.totalUnits() ? "COMPLETED"
                : plan.cancelRequested() ? (done > 0 ? "PARTIAL" : "CANCELLED")
                : "PARTIAL";
        plans.forceTerminalForRepair(plan.id(), terminal, "对账修复计划终态", now);
        plans.refreshCounts(plan.id(), done, failed, skipped, now);
    }

    private static Map<String, Object> movement(String itemCode, long qty, String refNo, String kinds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemCode", itemCode);
        m.put("qty", qty);
        m.put("refNo", refNo);
        m.put("kinds", kinds);
        return m;
    }

    private RepairResult toResult(RepairRepository.RepairRow row, boolean replayed) {
        List<Map<String, Object>> movements = new ArrayList<>();
        try {
            var node = Json.MAPPER.readTree(row.detailJson() == null || row.detailJson().isBlank()
                    ? "{}" : row.detailJson());
            if (node.has("movements")) {
                for (var mn : node.get("movements")) {
                    movements.add(Json.MAPPER.convertValue(mn, Map.class));
                }
            }
        } catch (Exception ignored) {
            // detail is best-effort on replay
        }
        return new RepairResult(row.repairNo(), row.result(), row.issueType(), row.planNo(),
                row.unitNo(), row.repairNo(), movements, replayed);
    }

    private static String writeJson(Object value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
