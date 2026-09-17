package com.gameops.craft.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Persisted operator repairs. A repair only ever appends a COMPENSATE ledger
 * row; the unique repair_key makes the same repair request safely retryable and
 * unique(comp_ref_no) ties it 1:1 to the posted compensation.
 */
@Repository
public class PlanRepairRepository {

    public record RepairRow(long id, String repairKey, String planNo, Integer unitNo,
                            String diffType, String itemCode, long qtyDelta, String compRefNo,
                            long operatorId, String remark, Instant createdAt) {}

    private static final RowMapper<RepairRow> MAPPER = (rs, n) -> new RepairRow(
            rs.getLong("id"), rs.getString("repair_key"), rs.getString("plan_no"),
            (Integer) rs.getObject("unit_no"), rs.getString("diff_type"), rs.getString("item_code"),
            rs.getLong("qty_delta"), rs.getString("comp_ref_no"), rs.getLong("operator_id"),
            rs.getString("remark"), PlanRepository.instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public PlanRepairRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when an identical repair request already exists (safe retry replay). */
    public boolean tryInsert(String repairKey, String planNo, Integer unitNo, String diffType,
                             String itemCode, long qtyDelta, String compRefNo,
                             long operatorId, String remark, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO craft_plan_repair
                      (repair_key, plan_no, unit_no, diff_type, item_code, qty_delta,
                       comp_ref_no, operator_id, remark, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, repairKey, planNo, unitNo, diffType, itemCode, qtyDelta, compRefNo,
                    operatorId, remark, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<RepairRow> findByKey(String repairKey) {
        return jdbc.query("SELECT * FROM craft_plan_repair WHERE repair_key = ?",
                MAPPER, repairKey).stream().findFirst();
    }

    public List<RepairRow> listRecent(int limit) {
        return jdbc.query("SELECT * FROM craft_plan_repair ORDER BY id DESC LIMIT ?",
                MAPPER, limit);
    }

    public List<RepairRow> listByPlan(String planNo) {
        return jdbc.query("SELECT * FROM craft_plan_repair WHERE plan_no = ? ORDER BY id",
                MAPPER, planNo);
    }
}
