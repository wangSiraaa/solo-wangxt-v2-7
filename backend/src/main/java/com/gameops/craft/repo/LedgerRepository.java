package com.gameops.craft.repo;

import com.gameops.craft.domain.LedgerEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LedgerEntry> MAPPER = (rs, n) -> map(rs);

    private static LedgerEntry map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        int unitNo = rs.getInt("unit_no");
        return new LedgerEntry(rs.getLong("id"), rs.getString("ref_no"), rs.getLong("player_id"),
                rs.getString("item_code"), rs.getString("entry_type"), rs.getLong("qty_delta"),
                rs.getString("related_ref"), rs.getString("status"), rs.getString("remark"),
                rs.getString("plan_no"), rs.wasNull() ? null : unitNo,
                created == null ? null : created.toInstant());
    }

    public void insert(String refNo, long playerId, String itemCode, String entryType,
                       long qtyDelta, String relatedRef, String status, String remark, Instant now) {
        insert(refNo, playerId, itemCode, entryType, qtyDelta, relatedRef, status, remark, now, null, null);
    }

    /** Batch-plan variant: every movement is traceable via (plan_no, unit_no). */
    public void insert(String refNo, long playerId, String itemCode, String entryType,
                       long qtyDelta, String relatedRef, String status, String remark, Instant now,
                       String planNo, Integer unitNo) {
        jdbc.update("""
                INSERT INTO ledger_entry
                  (ref_no, player_id, item_code, entry_type, qty_delta, related_ref, status,
                   remark, plan_no, unit_no, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, refNo, playerId, itemCode, entryType, qtyDelta, relatedRef, status, remark,
                planNo, unitNo, Timestamp.from(now));
    }

    public boolean exists(String refNo, long playerId, String itemCode, String entryType) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entry
                 WHERE ref_no = ? AND player_id = ? AND item_code = ? AND entry_type = ?
                """, Integer.class, refNo, playerId, itemCode, entryType);
        return n != null && n > 0;
    }

    /** All rows of one batch plan (compensation rows included). */
    public List<LedgerEntry> listByPlan(String planNo) {
        return jdbc.query(
                "SELECT * FROM ledger_entry WHERE plan_no = ? ORDER BY unit_no, id",
                MAPPER, planNo);
    }

    public List<LedgerEntry> listByRef(String refNo) {
        return jdbc.query(
                "SELECT * FROM ledger_entry WHERE ref_no = ? OR related_ref = ? ORDER BY id",
                MAPPER, refNo, refNo);
    }

    public List<LedgerEntry> listByPlayer(long playerId, int limit) {
        return jdbc.query("SELECT * FROM ledger_entry WHERE player_id = ? ORDER BY id DESC LIMIT ?",
                MAPPER, playerId, limit);
    }

    /** Produce lines of one committed order — exactly what a revocation must reverse. */
    public List<LedgerEntry> findProducesByOrderNo(String orderNo) {
        return jdbc.query(
                "SELECT * FROM ledger_entry WHERE ref_no = ? AND entry_type = 'PRODUCE' AND status = 'POSTED'",
                MAPPER, orderNo);
    }

    public List<LedgerEntry> listRecent(int limit) {
        return jdbc.query("SELECT * FROM ledger_entry ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }
}
