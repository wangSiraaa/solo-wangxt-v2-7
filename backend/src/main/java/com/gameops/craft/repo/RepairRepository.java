package com.gameops.craft.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RepairRepository {

    public record RepairRow(long id, String repairNo, String idempotencyKey, String planNo,
                            Integer unitNo, String orderNo, String issueType, String result,
                            String detailJson, long operatorId, Instant createdAt) {}

    private final JdbcTemplate jdbc;

    public RepairRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserve the repair idempotency key as IN_FLIGHT-ish row (result APPLIED/NOOP is filled in
     * the same tx later). Duplicate key => the identical repair request already ran/is running:
     * caller replays it and MUST NOT create a second compensation.
     */
    public boolean tryInsert(String repairNo, String idempotencyKey, String planNo, Integer unitNo,
                             String orderNo, String issueType, String result, String detailJson,
                             long operatorId, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO repair_record
                      (repair_no, idempotency_key, plan_no, unit_no, order_no, issue_type,
                       result, detail_json, operator_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, repairNo, idempotencyKey, planNo, unitNo, orderNo, issueType,
                    result, detailJson, operatorId, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<RepairRow> findByIdemKey(String key) {
        return jdbc.query("SELECT * FROM repair_record WHERE idempotency_key = ?",
                (rs, n) -> map(rs), key).stream().findFirst();
    }

    /**
     * Claim a PENDING reservation for phase-2 application. CAS PENDING -> APPLYING means at most
     * one process (including a crash-recovery retry) performs the compensation.
     */
    public int claimForApply(String repairNo) {
        return jdbc.update(
                "UPDATE repair_record SET result = 'APPLYING' WHERE repair_no = ? AND result = 'PENDING'",
                repairNo);
    }

    public void complete(String repairNo, String result, String detailJson, Instant now) {
        jdbc.update("UPDATE repair_record SET result = ?, detail_json = ? WHERE repair_no = ?",
                result, detailJson, repairNo);
    }

    public Optional<RepairRow> findByRepairNo(String repairNo) {
        return jdbc.query("SELECT * FROM repair_record WHERE repair_no = ?",
                (rs, n) -> map(rs), repairNo).stream().findFirst();
    }

    public List<RepairRow> listRecent(int limit) {
        return jdbc.query("SELECT * FROM repair_record ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), limit);
    }

    public List<RepairRow> listByPlan(String planNo) {
        return jdbc.query("SELECT * FROM repair_record WHERE plan_no = ? ORDER BY id",
                (rs, n) -> map(rs), planNo);
    }

    private static RepairRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int unitNo = rs.getInt("unit_no");
        Timestamp created = rs.getTimestamp("created_at");
        return new RepairRow(rs.getLong("id"), rs.getString("repair_no"),
                rs.getString("idempotency_key"), rs.getString("plan_no"),
                rs.wasNull() ? null : unitNo, rs.getString("order_no"),
                rs.getString("issue_type"), rs.getString("result"),
                rs.getString("detail_json"), rs.getLong("operator_id"),
                created == null ? null : created.toInstant());
    }
}
