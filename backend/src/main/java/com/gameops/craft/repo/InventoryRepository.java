package com.gameops.craft.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Balance mutations use only row-locked reads + UPDATE ... WHERE qty >= need.
 * The DB CHECK constraint (qty >= 0) is the final backstop against over-deduction.
 */
@Repository
public class InventoryRepository {

    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ItemQtyRow(String itemCode, long qty) {}

    /** SELECT ... FOR UPDATE: caller must hold a transaction. */
    public long lockAndGet(long playerId, String itemCode) {
        List<Long> rows = jdbc.query(
                "SELECT qty FROM player_inventory WHERE player_id = ? AND item_code = ? FOR UPDATE",
                (rs, n) -> rs.getLong("qty"), playerId, itemCode);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /**
     * Conditional deduction; rows-affected == 0 means insufficient stock.
     * The predicate is evaluated atomically under the row lock.
     */
    public int deductIfEnough(long playerId, String itemCode, long need, Instant now) {
        int updated = jdbc.update(
                "UPDATE player_inventory SET qty = qty - ?, updated_at = ? WHERE player_id = ? AND item_code = ? AND qty >= ?",
                need, Timestamp.from(now), playerId, itemCode, need);
        if (updated == 0) {
            // Row may not exist yet (balance treated as 0) — never auto-create a negative row.
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM player_inventory WHERE player_id = ? AND item_code = ?",
                    Integer.class, playerId, itemCode);
            if (exists == null || exists == 0) {
                return 0;
            }
        }
        return updated;
    }

    public void credit(long playerId, String itemCode, long amount, Instant now) {
        int updated = jdbc.update(
                "UPDATE player_inventory SET qty = qty + ?, updated_at = ? WHERE player_id = ? AND item_code = ?",
                amount, Timestamp.from(now), playerId, itemCode);
        if (updated == 0) {
            jdbc.update("INSERT INTO player_inventory (player_id, item_code, qty, updated_at) VALUES (?,?,?,?)",
                    playerId, itemCode, amount, Timestamp.from(now));
        }
    }

    /** Idempotency-safe grant: operator sets an absolute balance for a player/item. */
    public void upsertBalance(long playerId, String itemCode, long qty, Instant now) {
        int updated = jdbc.update(
                "UPDATE player_inventory SET qty = ?, updated_at = ? WHERE player_id = ? AND item_code = ?",
                qty, Timestamp.from(now), playerId, itemCode);
        if (updated == 0) {
            jdbc.update("INSERT INTO player_inventory (player_id, item_code, qty, updated_at) VALUES (?,?,?,?)",
                    playerId, itemCode, qty, Timestamp.from(now));
        }
    }

    public List<ItemQtyRow> list(long playerId) {
        return jdbc.query("SELECT item_code, qty FROM player_inventory WHERE player_id = ? ORDER BY item_code",
                (rs, n) -> new ItemQtyRow(rs.getString("item_code"), rs.getLong("qty")), playerId);
    }

    public long getQty(long playerId, String itemCode) {
        try {
            Long q = jdbc.queryForObject(
                    "SELECT qty FROM player_inventory WHERE player_id = ? AND item_code = ?",
                    Long.class, playerId, itemCode);
            return q == null ? 0L : q;
        } catch (EmptyResultDataAccessException e) {
            return 0L;
        }
    }

    public Map<String, Long> mapFor(long playerId, List<String> itemCodes) {
        if (itemCodes.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", itemCodes.stream().map(c -> "?").toList());
        Map<String, Long> result = new java.util.HashMap<>();
        Object[] params = new Object[itemCodes.size() + 1];
        params[0] = playerId;
        for (int i = 0; i < itemCodes.size(); i++) {
            params[i + 1] = itemCodes.get(i);
        }
        jdbc.query("SELECT item_code, qty FROM player_inventory WHERE player_id = ? AND item_code IN ("
                        + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.put(rs.getString("item_code"), rs.getLong("qty")), params);
        return result;
    }
}
