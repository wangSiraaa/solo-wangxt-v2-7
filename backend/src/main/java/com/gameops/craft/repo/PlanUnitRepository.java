package com.gameops.craft.repo;

import com.gameops.craft.domain.PlanUnit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lease + CAS primitives for plan units.
 *
 * Every state transition is a conditional UPDATE on the current status (and lease owner);
 * two workers can therefore touch the same row but at most one wins each transition.
 * Expired leases are reclaimable by ANY worker — correctness never depends on one process.
 */
@Repository
public class PlanUnitRepository {

    private final JdbcTemplate jdbc;

    public PlanUnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = """
            SELECT id, plan_id, plan_no, unit_no, player_id, recipe_id, recipe_version_id,
                   order_no, status, status_reason, lease_owner, lease_expires_at, attempts,
                   started_at, deducted_at, completed_at, created_at
              FROM plan_unit
            """;

    public void insert(long planId, String planNo, int unitNo, long playerId,
                       long recipeId, long versionId, Instant now) {
        jdbc.update("""
                INSERT INTO plan_unit
                  (plan_id, plan_no, unit_no, player_id, recipe_id, recipe_version_id,
                   status, attempts, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
                """, planId, planNo, unitNo, playerId, recipeId, versionId, Timestamp.from(now));
    }

    public Optional<PlanUnit> findByIdForUpdate(long unitId) {
        return jdbc.query(COLS + " WHERE id = ? FOR UPDATE",
                (rs, n) -> map(rs), unitId).stream().findFirst();
    }

    public List<PlanUnit> listByPlan(long planId) {
        return jdbc.query(COLS + " WHERE plan_id = ? ORDER BY unit_no",
                (rs, n) -> map(rs), planId);
    }

    public Optional<PlanUnit> findByOrderNo(String orderNo) {
        return jdbc.query(COLS + " WHERE order_no = ?",
                (rs, n) -> map(rs), orderNo).stream().findFirst();
    }

    /**
     * Fresh-work PROBE: PENDING units of plans still accepting new sequence numbers.
     * This is deliberately a NON-locking read — it only picks candidates. The authoritative
     * decision and row locks (plan header first, then unit) happen in acquireUnit, which
     * re-evaluates status and the stop gate, so a concurrent cancel never deadlocks here.
     */
    public List<PlanUnit> listPendingCandidates(int batch) {
        return jdbc.query("""
                SELECT u.id, u.plan_id, u.plan_no, u.unit_no, u.player_id, u.recipe_id,
                       u.recipe_version_id, u.order_no, u.status, u.status_reason,
                       u.lease_owner, u.lease_expires_at, u.attempts,
                       u.started_at, u.deducted_at, u.completed_at, u.created_at
                  FROM plan_unit u
                  JOIN batch_plan p ON p.id = u.plan_id
                 WHERE u.status = 'PENDING' AND p.stop_new_units = 0
                       AND p.status IN ('PENDING','RUNNING')
                 ORDER BY u.id
                 LIMIT ?
                """, (rs, n) -> map(rs), batch);
    }

    /**
     * Crash-recovery PROBE (non-locking): leased rows whose lease expired. Any worker may pick
     * them up; the authoritative reacquire (plan-first, then unit row) happens in acquireUnit.
     */
    public List<PlanUnit> listExpiredLeases(Instant now, int batch) {
        return jdbc.query("""
                SELECT id, plan_id, plan_no, unit_no, player_id, recipe_id, recipe_version_id,
                       order_no, status, status_reason, lease_owner, lease_expires_at, attempts,
                       started_at, deducted_at, completed_at, created_at
                  FROM plan_unit
                 WHERE status IN ('RUNNING','DEDUCTED') AND lease_expires_at < ?
                 ORDER BY id
                 LIMIT ?
                """, (rs, n) -> map(rs), Timestamp.from(now), batch);
    }

    /** CAS: PENDING -> RUNNING with a new lease + the craft_order created for this unit. */
    public int casClaim(PlanUnit u, String owner, String orderNo, Instant leaseStart, Instant leaseExpiry) {
        return jdbc.update("""
                UPDATE plan_unit
                   SET status = 'RUNNING', lease_owner = ?, lease_expires_at = ?,
                       order_no = ?, attempts = attempts + 1,
                       started_at = COALESCE(started_at, ?)
                 WHERE id = ? AND status = 'PENDING'
                """, owner, Timestamp.from(leaseExpiry), orderNo, Timestamp.from(leaseStart), u.id());
    }

    /** Re-lease an expired RUNNING/DEDUCTED row for recovery. */
    public int reacquire(PlanUnit u, String owner, Instant leaseStart, Instant leaseExpiry) {
        return jdbc.update("""
                UPDATE plan_unit
                   SET lease_owner = ?, lease_expires_at = ?, attempts = attempts + 1,
                       started_at = COALESCE(started_at, ?)
                 WHERE id = ? AND status IN ('RUNNING','DEDUCTED')
                       AND (lease_expires_at IS NULL OR lease_expires_at < ?)
                """, owner, Timestamp.from(leaseExpiry), Timestamp.from(leaseStart),
                u.id(), Timestamp.from(leaseStart));
    }

    /** Tx1 durable checkpoint: materials are consumed. */
    public int casDeducted(long unitId, String owner, Instant now) {
        return jdbc.update("""
                UPDATE plan_unit SET status = 'DEDUCTED', deducted_at = ?
                 WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """, Timestamp.from(now), unitId, owner);
    }

    /** Tx3 final checkpoint: rewards were issued in Tx2; clear the lease. */
    public int casDone(long unitId, String owner, Instant now) {
        return jdbc.update("""
                UPDATE plan_unit
                   SET status = 'DONE', completed_at = ?, lease_owner = NULL, lease_expires_at = NULL
                 WHERE id = ? AND status IN ('RUNNING','DEDUCTED')
                       AND (lease_owner = ? OR lease_owner IS NULL)
                """, Timestamp.from(now), unitId, owner);
    }

    /** Recovery path for the "produced but status not written back" window. */
    public int casDoneWithoutLease(long unitId, Instant now) {
        return jdbc.update("""
                UPDATE plan_unit
                   SET status = 'DONE', completed_at = COALESCE(completed_at, ?),
                       lease_owner = NULL, lease_expires_at = NULL
                 WHERE id = ? AND status IN ('RUNNING','DEDUCTED')
                """, Timestamp.from(now), unitId);
    }

    /** Never-started units only; the PENDING predicate is the cancel/claim race arbiter. */
    public int bulkSkipPending(long planId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE plan_unit
                   SET status = 'SKIPPED', status_reason = ?, lease_owner = NULL,
                       lease_expires_at = NULL
                 WHERE plan_id = ? AND status = 'PENDING'
                """, reason, planId);
    }

    /**
     * After a shortage freeze, other units claimed in the same batch may be RUNNING with a
     * lease but no deduction yet. They are skipped at once (not after lease expiry); a
     * deducted unit is never returned (its reward is still owed).
     */
    public List<long[]> findRunningUndeductedUnits(long planId) {
        return jdbc.query("""
                SELECT u.id AS id,
                       (SELECT COUNT(*) FROM ledger_entry e
                         WHERE e.ref_no = u.order_no AND e.entry_type = 'CONSUME') AS consumed
                  FROM plan_unit u
                 WHERE u.plan_id = ? AND u.status = 'RUNNING' AND u.order_no IS NOT NULL
                """,
                (rs, n) -> new long[]{rs.getLong("id"), rs.getLong("consumed")}, planId);
    }

    /** Unconditional skip for a currently RUNNING unit during a same-tick shortage freeze. */
    public int forceSkipRunning(long unitId, String reason) {
        return jdbc.update("""
                UPDATE plan_unit
                   SET status = 'SKIPPED', status_reason = ?, lease_owner = NULL,
                       lease_expires_at = NULL
                 WHERE id = ? AND status = 'RUNNING'
                """, reason, unitId);
    }

    public int casFailed(long unitId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE plan_unit
                   SET status = 'FAILED', status_reason = ?, lease_owner = NULL,
                       lease_expires_at = NULL
                 WHERE id = ? AND status IN ('RUNNING','DEDUCTED')
                """, reason, Timestamp.from(now), unitId);
    }

    /** Live counts of each status; plan finalization reads these under the plan row lock. */
    public List<StatusCount> countByStatus(long planId) {
        return jdbc.query(
                "SELECT status, COUNT(*) AS n FROM plan_unit WHERE plan_id = ? GROUP BY status",
                (rs, n) -> new StatusCount(rs.getString("status"), rs.getInt("n")), planId);
    }

    public record StatusCount(String status, int count) {}

    private static PlanUnit map(ResultSet rs) throws SQLException {
        Timestamp lease = rs.getTimestamp("lease_expires_at");
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp deducted = rs.getTimestamp("deducted_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        Timestamp created = rs.getTimestamp("created_at");
        return new PlanUnit(
                rs.getLong("id"), rs.getLong("plan_id"), rs.getString("plan_no"),
                rs.getInt("unit_no"), rs.getLong("player_id"), rs.getLong("recipe_id"),
                rs.getLong("recipe_version_id"), rs.getString("order_no"),
                rs.getString("status"), rs.getString("status_reason"),
                rs.getString("lease_owner"),
                lease == null ? null : lease.toInstant(),
                rs.getInt("attempts"),
                started == null ? null : started.toInstant(),
                deducted == null ? null : deducted.toInstant(),
                completed == null ? null : completed.toInstant(),
                created == null ? null : created.toInstant());
    }
}
