package com.gameops.craft.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RevokeRepository {

    public record RevokeRow(long id, String revokeNo, long orderId, String orderNo, long playerId,
                            String result, String shortageJson, long operatorId, Instant createdAt) {}

    private final JdbcTemplate jdbc;

    public RevokeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String revokeNo, long orderId, String orderNo, long playerId,
                       String result, String shortageJson, long operatorId, Instant now) {
        jdbc.update("""
                INSERT INTO revoke_record
                  (revoke_no, order_id, order_no, player_id, result, shortage_json, operator_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, revokeNo, orderId, orderNo, playerId, result, shortageJson, operatorId,
                Timestamp.from(now));
    }

    public List<RevokeRow> listByResult(String result) {
        return jdbc.query(
                "SELECT * FROM revoke_record WHERE result = ? ORDER BY id DESC",
                (rs, n) -> new RevokeRow(rs.getLong("id"), rs.getString("revoke_no"),
                        rs.getLong("order_id"), rs.getString("order_no"), rs.getLong("player_id"),
                        rs.getString("result"), rs.getString("shortage_json"),
                        rs.getLong("operator_id"), rs.getTimestamp("created_at").toInstant()),
                result);
    }

    public List<RevokeRow> listRecent(int limit) {
        return jdbc.query("SELECT * FROM revoke_record ORDER BY id DESC LIMIT ?",
                (rs, n) -> new RevokeRow(rs.getLong("id"), rs.getString("revoke_no"),
                        rs.getLong("order_id"), rs.getString("order_no"), rs.getLong("player_id"),
                        rs.getString("result"), rs.getString("shortage_json"),
                        rs.getLong("operator_id"), rs.getTimestamp("created_at").toInstant()),
                limit);
    }
}
