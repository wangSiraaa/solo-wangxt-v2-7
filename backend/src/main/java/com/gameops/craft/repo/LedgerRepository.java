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
        Integer unitNo = (Integer) rs.getObject("unit_no");
        return new LedgerEntry(rs.getLong("id"), rs.getString("ref_no"), rs.getLong("player_id"),
                rs.getString("item_code"), rs.getString("entry_type"), rs.getLong("qty_delta"),
                rs.getString("related_ref"), rs.getString("plan_no"), unitNo,
                rs.getString("status"), rs.getString("remark"),
                created == null ? null : created.toInstant());
    }

    public void insert(String refNo, long playerId, String itemCode, String entryType,
                       long qtyDelta, String relatedRef, String status, String remark, Instant now) {
        insert(refNo, playerId, itemCode, entryType, qtyDelta, relatedRef, null, null,
                status, remark, now);
    }

    /** Full form used by batch-plan units: every movement carries plan_no/unit_no. */
    public void insert(String refNo, long playerId, String itemCode, String entryType,
                       long qtyDelta, String relatedRef, String planNo, Integer unitNo,
                       String status, String remark, Instant now) {
        jdbc.update("""
                INSERT INTO ledger_entry
                  (ref_no, player_id, item_code, entry_type, qty_delta, related_ref,
                   plan_no, unit_no, status, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, refNo, playerId, itemCode, entryType, qtyDelta, relatedRef,
                planNo, unitNo, status, remark, Timestamp.from(now));
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

    /** Reconciliation: every posted ledger line of one batch plan (all its units). */
    public List<LedgerEntry> listByPlanNo(String planNo) {
        return jdbc.query(
                "SELECT * FROM ledger_entry WHERE plan_no = ? ORDER BY id", MAPPER, planNo);
    }

    /** Lines of one unit's order (CONSUME / PRODUCE / RELEASE / compensation all share ref_no). */
    public List<LedgerEntry> listByRefForUpdate(String refNo) {
        return jdbc.query(
                "SELECT * FROM ledger_entry WHERE ref_no = ? ORDER BY id FOR UPDATE", MAPPER, refNo);
    }
}
