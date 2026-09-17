package com.gameops.craft.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    public record UserRow(long id, String username, String passwordHash, String role, String displayName) {}

    private static final RowMapper<UserRow> USER_MAPPER = (rs, n) -> new UserRow(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
            rs.getString("role"), rs.getString("display_name"));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByUsername(String username) {
        return jdbc.query("SELECT id, username, password_hash, role, display_name FROM app_user WHERE username = ?",
                USER_MAPPER, username).stream().findFirst();
    }

    public Optional<UserRow> findById(long id) {
        return jdbc.query("SELECT id, username, password_hash, role, display_name FROM app_user WHERE id = ?",
                USER_MAPPER, id).stream().findFirst();
    }

    public void insertToken(String token, long userId, String role, Instant now, Instant expiresAt) {
        jdbc.update("INSERT INTO login_token (token, user_id, role, expires_at, created_at) VALUES (?,?,?,?,?)",
                token, userId, role, Timestamp.from(expiresAt), Timestamp.from(now));
    }

    public record TokenRow(long userId, String role) {}

    /** Token lookup; expired rows are treated as missing. */
    public Optional<TokenRow> findValidToken(String token, Instant now) {
        return jdbc.query(
                "SELECT user_id, role FROM login_token WHERE token = ? AND expires_at > ?",
                (rs, n) -> new TokenRow(rs.getLong("user_id"), rs.getString("role")),
                token, Timestamp.from(now)).stream().findFirst();
    }

    public String roleOfUserId(long userId) {
        return jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, userId);
    }
}
