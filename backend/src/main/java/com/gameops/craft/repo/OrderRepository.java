package com.gameops.craft.repo;

import com.gameops.craft.domain.CraftOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLS = """
            SELECT o.id, o.order_no, o.player_id, o.recipe_id, o.recipe_version_id,
                   rv.version_no, r.code AS recipe_code, r.name AS recipe_name,
                   o.status, o.status_reason, o.preoccupy_deadline, o.committed_at,
                   o.closed_at, o.revoke_ref_no, o.created_at
              FROM craft_order o
              JOIN recipe r ON r.id = o.recipe_id
              JOIN recipe_version rv ON rv.id = o.recipe_version_id
            """;

    private static final RowMapper<CraftOrder> MAPPER = (rs, n) -> map(rs);

    private static CraftOrder map(ResultSet rs) throws SQLException {
        Timestamp dl = rs.getTimestamp("preoccupy_deadline");
        Timestamp committed = rs.getTimestamp("committed_at");
        Timestamp closed = rs.getTimestamp("closed_at");
        Timestamp created = rs.getTimestamp("created_at");
        return new CraftOrder(
                rs.getLong("id"), rs.getString("order_no"), rs.getLong("player_id"),
                rs.getLong("recipe_id"), rs.getLong("recipe_version_id"),
                rs.getInt("version_no"), rs.getString("recipe_code"), rs.getString("recipe_name"),
                rs.getString("status"), rs.getString("status_reason"),
                dl == null ? null : dl.toInstant(),
                committed == null ? null : committed.toInstant(),
                closed == null ? null : closed.toInstant(),
                rs.getString("revoke_ref_no"),
                created == null ? null : created.toInstant());
    }

    public long insert(String orderNo, long playerId, long recipeId, long versionId,
                       Instant deadline, Instant now) {
        jdbc.update("""
                INSERT INTO craft_order
                  (order_no, player_id, recipe_id, recipe_version_id, status,
                   preoccupy_deadline, committed_at, closed_at, created_at)
                VALUES (?, ?, ?, ?, 'PREOCCUPIED', ?, NULL, NULL, ?)
                """, orderNo, playerId, recipeId, versionId,
                Timestamp.from(deadline), Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM craft_order WHERE order_no = ?", Long.class, orderNo);
    }

    /** Pessimistic lock on the order row; state transitions are compare-and-swap by status. */
    public Optional<CraftOrder> findByNoForUpdate(String orderNo) {
        List<CraftOrder> rows = jdbc.query(
                "SELECT o.*, rv.version_no AS version_no, r.code AS recipe_code, r.name AS recipe_name "
                        + "FROM craft_order o JOIN recipe r ON r.id = o.recipe_id "
                        + "JOIN recipe_version rv ON rv.id = o.recipe_version_id "
                        + "WHERE o.order_no = ? FOR UPDATE",
                (rs, n) -> map(rs), orderNo);
        return rows.stream().findFirst();
    }

    public Optional<CraftOrder> findByNo(String orderNo) {
        return jdbc.query(SELECT_COLS + " WHERE o.order_no = ?", MAPPER, orderNo)
                .stream().findFirst();
    }

    /** CAS commit: only an order still PREOCCUPIED (and within deadline) can be committed. */
    public int casCommit(long orderId, Instant now, Instant deadline) {
        return jdbc.update("""
                UPDATE craft_order
                   SET status = 'COMMITTED', committed_at = ?, closed_at = ?
                 WHERE id = ? AND status = 'PREOCCUPIED' AND preoccupy_deadline >= ?
                """, Timestamp.from(now), Timestamp.from(now), orderId, Timestamp.from(deadline));
    }

    public int casCancel(long orderId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE craft_order
                   SET status = 'CANCELLED', status_reason = ?, closed_at = ?
                 WHERE id = ? AND status = 'PREOCCUPIED'
                """, reason, Timestamp.from(now), orderId);
    }

    /** Sweeper CAS — deadline must be in the past, so a concurrent commit loses cleanly. */
    public int casTimeout(long orderId, Instant now) {
        return jdbc.update("""
                UPDATE craft_order
                   SET status = 'TIMEOUT', status_reason = 'preoccupy deadline expired', closed_at = ?
                 WHERE id = ? AND status = 'PREOCCUPIED' AND preoccupy_deadline < ?
                """, Timestamp.from(now), orderId, Timestamp.from(now));
    }

    public int casRevoke(long orderId, String revokeNo, Instant now) {
        return jdbc.update("""
                UPDATE craft_order
                   SET status = 'REVOKED', revoke_ref_no = ?, closed_at = ?
                 WHERE id = ? AND status = 'COMMITTED'
                """, revokeNo, Timestamp.from(now), orderId);
    }

    public List<CraftOrder> listByPlayer(long playerId, int limit) {
        return jdbc.query(SELECT_COLS + " WHERE o.player_id = ? ORDER BY o.id DESC LIMIT ?",
                MAPPER, playerId, limit);
    }

    /** Orders the timeout sweeper should try to close. */
    public List<CraftOrder> listExpiredPreoccupied(Instant now, int batch) {
        return jdbc.query("""
                SELECT o.*, rv.version_no AS version_no, r.code AS recipe_code, r.name AS recipe_name
                  FROM craft_order o
                  JOIN recipe r ON r.id = o.recipe_id
                  JOIN recipe_version rv ON rv.id = o.recipe_version_id
                 WHERE o.status = 'PREOCCUPIED' AND o.preoccupy_deadline < ?
                 ORDER BY o.id LIMIT ?
                """, (rs, n) -> map(rs), Timestamp.from(now), batch);
    }

    public List<CraftOrder> listRecent(int limit) {
        return jdbc.query(SELECT_COLS + " ORDER BY o.id DESC LIMIT ?", MAPPER, limit);
    }
}
