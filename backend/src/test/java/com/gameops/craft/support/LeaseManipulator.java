package com.gameops.craft.support;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Test-only helper that moves a plan unit's lease expiry into the past,
 * simulating a crashed worker without waiting out the real lease TTL.
 */
@Component
public class LeaseManipulator {

    private final JdbcTemplate jdbc;

    public LeaseManipulator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Mark this unit's lease as already expired (worker crashed mid-unit). */
    public void expireLease(long unitId) {
        jdbc.update("UPDATE craft_plan_unit SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), unitId);
    }

    public void expireLeaseByOrderNo(String orderNo) {
        jdbc.update("UPDATE craft_plan_unit SET lease_expires_at = ? WHERE order_no = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), orderNo);
    }

    /** Force a unit into LEASED state with an identifiable dead owner. */
    public void forceLease(long unitId, String owner, String token, Instant expiresAt) {
        jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'LEASED', lease_owner = ?, lease_token = ?,
                       leased_at = ?, lease_expires_at = ?, attempts = attempts + 1
                 WHERE id = ?
                """, owner, token, Timestamp.from(Instant.now()), Timestamp.from(expiresAt), unitId);
    }
}
