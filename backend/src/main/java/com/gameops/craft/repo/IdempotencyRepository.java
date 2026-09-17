package com.gameops.craft.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record IdemRow(String idempotencyKey, long playerId, String scope, String refNo,
                          String responseJson, String status, Instant updatedAt) {}

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserve the key. Returning false (duplicate key) means another request already owns it;
     * caller then reads the outcome (or rejects an in-flight duplicate).
     */
    public boolean tryReserve(String key, long playerId, String scope, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO idempotency_record
                      (idempotency_key, player_id, scope, ref_no, response_json, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, '', 'IN_FLIGHT', ?, ?)
                    """, key, playerId, scope, "", Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<IdemRow> find(String key) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE idempotency_key = ?",
                (rs, n) -> new IdemRow(rs.getString("idempotency_key"), rs.getLong("player_id"),
                        rs.getString("scope"), rs.getString("ref_no"), rs.getString("response_json"),
                        rs.getString("status"), rs.getTimestamp("updated_at").toInstant()),
                key).stream().findFirst();
    }

    public void complete(String key, String refNo, String responseJson, Instant now) {
        jdbc.update("""
                UPDATE idempotency_record SET ref_no = ?, response_json = ?, status = 'DONE', updated_at = ?
                 WHERE idempotency_key = ?
                """, refNo, responseJson, Timestamp.from(now), key);
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM idempotency_record WHERE idempotency_key = ? AND status = 'IN_FLIGHT'", key);
    }
}
