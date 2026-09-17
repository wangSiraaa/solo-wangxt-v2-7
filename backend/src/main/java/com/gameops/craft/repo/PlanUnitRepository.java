package com.gameops.craft.repo;

import com.gameops.craft.domain.CraftPlanUnit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Lease + CAS primitives for batch-plan units.
 *
 * Claiming is a compare-and-swap on (status, lease_expires_at):
 *   PENDING               -> LEASED (only when the plan has not stopped)
 *   LEASED + expired lease-> LEASED (crash recovery)
 * The old owner's completion CAS additionally requires its lease_token, so a
 * dead worker can never write DONE after its lease was reclaimed.
 */
@Repository
public class PlanUnitRepository {

    private final JdbcTemplate jdbc;

    public PlanUnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CraftPlanUnit> MAPPER = (rs, n) -> map(rs);

    private static CraftPlanUnit map(ResultSet rs) throws SQLException {
        return new CraftPlanUnit(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                rs.getString("plan_no"),
                rs.getInt("unit_no"),
                rs.getString("status"),
                rs.getString("lease_owner"),
                rs.getString("lease_token"),
                PlanRepository.instant(rs, "leased_at"),
                PlanRepository.instant(rs, "lease_expires_at"),
                rs.getInt("attempts"),
                rs.getString("order_no"),
                PlanRepository.instant(rs, "consumed_at"),
                PlanRepository.instant(rs, "rewarded_at"),
                PlanRepository.instant(rs, "finished_at"),
                rs.getString("skip_reason"),
                rs.getString("fail_reason"),
                PlanRepository.instant(rs, "created_at"));
    }

    public void batchInsert(long planId, String planNo, int count, Instant now) {
        for (int i = 1; i <= count; i++) {
            jdbc.update("""
                    INSERT INTO craft_plan_unit
                      (plan_id, plan_no, unit_no, status, attempts, created_at)
                    VALUES (?, ?, ?, 'PENDING', 0, ?)
                    """, planId, planNo, i, Timestamp.from(now));
        }
    }

    public List<CraftPlanUnit> listByPlan(long planId) {
        return jdbc.query(
                "SELECT * FROM craft_plan_unit WHERE plan_id = ? ORDER BY unit_no", MAPPER, planId);
    }

    public Optional<CraftPlanUnit> findByIdForUpdate(long unitId) {
        return jdbc.query("SELECT * FROM craft_plan_unit WHERE id = ? FOR UPDATE",
                MAPPER, unitId).stream().findFirst();
    }

    /** Non-locking read (used to resolve planId before taking locks in order). */
    public Optional<CraftPlanUnit> findById(long unitId) {
        return jdbc.query("SELECT * FROM craft_plan_unit WHERE id = ?",
                MAPPER, unitId).stream().findFirst();
    }

    public List<CraftPlanUnit> findExpiredLeasedForClaim(long planId, Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM craft_plan_unit
                 WHERE plan_id = ? AND status = 'LEASED' AND lease_expires_at <= ?
                 ORDER BY unit_no
                 LIMIT ?
                """, MAPPER, planId, Timestamp.from(now), limit)
                .stream().toList();
    }

    /**
     * The next not-started unit, locked FOR UPDATE. Pending units are only picked
     * while the plan is RUNNING with no stop flag; expired leases are reclaimed
     * even after stop (a leased unit's reward must be settled, never abandoned).
     * The join also row-locks the plan header, serialising the pick against
     * cancel/stop (both lock the same plan row first, same global lock order).
     */
    /**
     * Candidate next not-started unit, locked on the UNIT row only (no join):
     * concurrent workers serialize on this single row and never take
     * cross-table locks. Whether the plan is still allowed to start is enforced
     * atomically by casLeasePending (which re-checks the plan header in its
     * WHERE clause), not by this read.
     */
    public Optional<CraftPlanUnit> lockNextPending(long planId) {
        return jdbc.query("""
                SELECT * FROM craft_plan_unit
                 WHERE plan_id = ? AND status = 'PENDING'
                 ORDER BY unit_no
                 LIMIT 1
                 FOR UPDATE
                """, MAPPER, planId).stream().findFirst();
    }

    /**
     * Atomic lease of a fresh unit. The plan-header sub-query is the gate:
     * zero rows when the plan is stopped/cancelled/no-longer-running, even
     * though the unit itself is still PENDING. Evaluated under the row lock.
     */
    public int casLeasePendingGated(long unitId, String owner, String token,
                                    Instant now, Instant expiresAt) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'LEASED', lease_owner = ?, lease_token = ?,
                       leased_at = ?, lease_expires_at = ?, attempts = attempts + 1
                 WHERE id = ? AND status = 'PENDING'
                       AND EXISTS (
                           SELECT 1 FROM craft_plan p
                            WHERE p.id = craft_plan_unit.plan_id
                              AND p.status = 'RUNNING' AND p.stop_flag = 0)
                """, owner, token, Timestamp.from(now), Timestamp.from(expiresAt), unitId);
    }

    /** CAS for a fresh PENDING unit; zero rows means a concurrent worker won. */
    public int casLeasePending(long unitId, String owner, String token,
                               Instant now, Instant expiresAt) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'LEASED', lease_owner = ?, lease_token = ?,
                       leased_at = ?, lease_expires_at = ?, attempts = attempts + 1
                 WHERE id = ? AND status = 'PENDING'
                """, owner, token, Timestamp.from(now), Timestamp.from(expiresAt), unitId);
    }

    /** Reclaim a crashed worker's expired lease (recovery path). */
    public int casLeaseExpired(long unitId, String owner, String token,
                               Instant now, Instant expiresAt) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'LEASED', lease_owner = ?, lease_token = ?,
                       leased_at = ?, lease_expires_at = ?, attempts = attempts + 1
                 WHERE id = ? AND status = 'LEASED' AND lease_expires_at <= ?
                """, owner, token, Timestamp.from(now), Timestamp.from(expiresAt),
                unitId, Timestamp.from(now));
    }

    /** Extend a live lease owned by this token (long-running tx safety). */
    public int renewLease(long unitId, String token, Instant expiresAt) {
        return jdbc.update("""
                UPDATE craft_plan_unit SET lease_expires_at = ?
                 WHERE id = ? AND status = 'LEASED' AND lease_token = ?
                """, Timestamp.from(expiresAt), unitId, token);
    }

    /** Bind the ordinary craft order exactly once; unique(order_no) also backs this. */
    public int bindOrder(long unitId, String orderNo, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET order_no = ?, consumed_at = ?
                 WHERE id = ? AND order_no IS NULL
                """, orderNo, Timestamp.from(now), unitId);
    }

    /** Mark that outputs were issued, but the unit status writeback has not happened yet. */
    public int markRewarded(long unitId, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan_unit SET rewarded_at = ?
                 WHERE id = ? AND rewarded_at IS NULL
                """, Timestamp.from(now), unitId);
    }

    /**
     * Completion CAS. Requires the caller's lease token: after lease expiry the
     * unit belongs to another worker and the dead caller loses the writeback.
     */
    public int casDone(long unitId, String token, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'DONE', finished_at = ?
                 WHERE id = ? AND status = 'LEASED' AND lease_token = ?
                """, Timestamp.from(now), unitId, token);
    }

    public int casSkip(long unitId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'SKIPPED', skip_reason = ?, finished_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, reason, Timestamp.from(now), unitId);
    }

    /**
     * A leased unit whose start gate failed and which never bound an order
     * (material deduction rolled back) becomes SKIPPED. The order_no IS NULL
     * predicate is essential: a crash-recovered LEASED unit that already
     * deducted materials is NOT skippable — it must be resumed and rewarded.
     */
    public int skipLeasedWithoutOrder(long unitId, String token, String reason, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'SKIPPED', skip_reason = ?, finished_at = ?
                 WHERE id = ? AND status = 'LEASED' AND lease_token = ? AND order_no IS NULL
                """, reason, Timestamp.from(now), unitId, token);
    }

    public int casFail(long unitId, String token, String reason, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'FAILED', fail_reason = ?, finished_at = ?
                 WHERE id = ? AND status = 'LEASED' AND lease_token = ?
                """, reason, Timestamp.from(now), unitId, token);
    }

    /** Cancel/stop path: every still-PENDING unit is skipped in one locked sweep. */
    public int skipAllPending(long planId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan_unit
                   SET status = 'SKIPPED', skip_reason = ?, finished_at = ?
                 WHERE plan_id = ? AND status = 'PENDING'
                """, reason, Timestamp.from(now), planId);
    }

    public long countByStatus(long planId, String status) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM craft_plan_unit WHERE plan_id = ? AND status = ?",
                Long.class, planId, status);
        return v == null ? 0 : v;
    }

    public boolean existsUnfinished(long planId) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM craft_plan_unit WHERE plan_id = ? AND status IN ('PENDING','LEASED')",
                Long.class, planId);
        return v != null && v > 0;
    }

    /** Reconciliation support: find units with a live lease far past expected expiry. */
    public List<CraftPlanUnit> listStuckLeased(Instant stuckBefore, int limit) {
        return jdbc.query("""
                SELECT * FROM craft_plan_unit
                 WHERE status = 'LEASED' AND lease_expires_at < ?
                 ORDER BY id LIMIT ?
                """, MAPPER, Timestamp.from(stuckBefore), limit);
    }
}
