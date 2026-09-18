package com.gameops.craft.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HoldRepository {

    public record HoldRow(long id, long orderId, long playerId, String itemCode, long qty) {}

    private final JdbcTemplate jdbc;

    public HoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(long orderId, long playerId, String itemCode, long qty, Instant now) {
        jdbc.update("""
                INSERT INTO material_hold (order_id, player_id, item_code, qty, released, created_at)
                VALUES (?, ?, ?, ?, 0, ?)
                """, orderId, playerId, itemCode, qty, Timestamp.from(now));
    }

    /** Locks hold rows for this order during commit/release so sweeper & request can't double-free. */
    public List<HoldRow> lockByOrder(long orderId) {
        return jdbc.query(
                "SELECT id, order_id, player_id, item_code, qty FROM material_hold WHERE order_id = ? FOR UPDATE",
                (rs, n) -> new HoldRow(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("player_id"),
                        rs.getString("item_code"), rs.getLong("qty")),
                orderId);
    }

    public List<HoldRow> listByOrder(long orderId) {
        return jdbc.query(
                "SELECT id, order_id, player_id, item_code, qty FROM material_hold WHERE order_id = ?",
                (rs, n) -> new HoldRow(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("player_id"),
                        rs.getString("item_code"), rs.getLong("qty")),
                orderId);
    }

    /** CAS-style release: unreleased rows only. Returns how many lines actually flipped. */
    public int markReleased(long holdId, Instant now) {
        return jdbc.update(
                "UPDATE material_hold SET released = 1, released_at = ? WHERE id = ? AND released = 0",
                Timestamp.from(now), holdId);
    }

    public List<HoldRow> listByPlayer(long playerId) {
        return jdbc.query("""
                SELECT h.id, h.order_id, h.player_id, h.item_code, h.qty
                  FROM material_hold h
                  JOIN craft_order o ON o.id = h.order_id
                 WHERE h.player_id = ? AND o.status = 'PREOCCUPIED' AND h.released = 0
                 ORDER BY h.id
                """, (rs, n) -> new HoldRow(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("player_id"),
                rs.getString("item_code"), rs.getLong("qty")), playerId);
    }

    /**
     * Batch-plan variant: consumed material is recorded as a hold as well, but the hold is
     * settled (marked released) inside the SAME deduction transaction — it never goes back to
     * the balance (no RELEASE); it is the audit anchor tying plan unit -> consumed materials.
     */
    public void createAndSettle(long orderId, long playerId, String itemCode, long qty, Instant now) {
        jdbc.update("""
                INSERT INTO material_hold (order_id, player_id, item_code, qty, released, created_at, released_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, orderId, playerId, itemCode, qty, Timestamp.from(now), Timestamp.from(now));
    }
}
