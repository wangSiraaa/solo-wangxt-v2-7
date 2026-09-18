package com.gameops.craft.repo;

import com.gameops.craft.domain.BatchPlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BatchPlanRepository {

    private final JdbcTemplate jdbc;

    public BatchPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLS = """
            SELECT p.id AS id, p.plan_no AS plan_no, p.player_id AS player_id, p.recipe_id AS recipe_id,
                   p.recipe_version_id AS recipe_version_id, rv.version_no AS version_no,
                   p.total_units AS total_units, p.inputs_json AS inputs_json,
                   p.outputs_json AS outputs_json, p.status AS status, p.status_reason AS status_reason,
                   p.stop_new_units AS stop_new_units, p.cancel_requested AS cancel_requested,
                   p.completed_count AS completed_count, p.failed_count AS failed_count,
                   p.skipped_count AS skipped_count, p.created_at AS created_at, p.finished_at AS finished_at
              FROM batch_plan p
              JOIN recipe_version rv ON rv.id = p.recipe_version_id
            """;

    public long insert(String planNo, long playerId, long recipeId, long versionId, int totalUnits,
                       String inputsJson, String outputsJson, Instant now) {
        jdbc.update("""
                INSERT INTO batch_plan
                  (plan_no, player_id, recipe_id, recipe_version_id, total_units,
                   inputs_json, outputs_json, status, stop_new_units, cancel_requested,
                   completed_count, failed_count, skipped_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 0, 0, 0, 0, ?)
                """, planNo, playerId, recipeId, versionId, totalUnits,
                inputsJson, outputsJson, Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM batch_plan WHERE plan_no = ?", Long.class, planNo);
    }

    /** Row lock + full row; used by worker/cancel/finalize decisions. */
    public Optional<BatchPlan> findByNoForUpdate(String planNo) {
        return jdbc.query(SELECT_COLS + " WHERE p.plan_no = ? FOR UPDATE",
                (rs, n) -> map(rs), planNo).stream().findFirst();
    }

    public Optional<BatchPlan> findByNo(String planNo) {
        return jdbc.query(SELECT_COLS + " WHERE p.plan_no = ?",
                (rs, n) -> map(rs), planNo).stream().findFirst();
    }

    public Optional<BatchPlan> findByIdForUpdate(long planId) {
        return jdbc.query(SELECT_COLS + " WHERE p.id = ? FOR UPDATE",
                (rs, n) -> map(rs), planId).stream().findFirst();
    }

    /** PENDING -> RUNNING once the first unit starts. */
    public int casRunning(long planId, Instant now) {
        return jdbc.update(
                "UPDATE batch_plan SET status = 'RUNNING' WHERE id = ? AND status = 'PENDING'",
                planId);
    }

    /**
     * Freeze the plan so no new unit numbers are claimed (activity ended / shortage / cancel).
     * Already-leased units keep running on their bound version. Idempotent.
     */
    public int markStopNewUnits(long planId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE batch_plan
                   SET stop_new_units = 1,
                       status_reason = COALESCE(status_reason, ?)
                 WHERE id = ? AND stop_new_units = 0 AND status NOT IN ('COMPLETED','PARTIAL','CANCELLED','FAILED')
                """, reason, planId);
    }

    public int markCancelRequested(long planId, Instant now) {
        return jdbc.update("""
                UPDATE batch_plan
                   SET cancel_requested = 1, stop_new_units = 1,
                       status_reason = COALESCE(status_reason, 'player cancelled')
                 WHERE id = ? AND status NOT IN ('COMPLETED','PARTIAL','CANCELLED','FAILED')
                """, planId);
    }

    /**
     * Terminal transition. The exact terminal status is computed by the service from
     * live unit counts under the plan row lock; only a non-terminal row can flip.
     */
    public int casTerminal(long planId, String status, String reason,
                           int completed, int failed, int skipped, Instant now) {
        return jdbc.update("""
                UPDATE batch_plan
                   SET status = ?, status_reason = ?, completed_count = ?,
                       failed_count = ?, skipped_count = ?, finished_at = ?
                 WHERE id = ? AND status NOT IN ('COMPLETED','PARTIAL','CANCELLED','FAILED')
                """, status, reason, completed, failed, skipped, Timestamp.from(now), planId);
    }

    /** Operator reconciliation repair: rewrite a wrong terminal status, counts unchanged here. */
    public int forceTerminalForRepair(long planId, String status, String reason, Instant now) {
        return jdbc.update("""
                UPDATE batch_plan
                   SET status = ?, status_reason = ?, finished_at = COALESCE(finished_at, ?)
                 WHERE id = ?
                """, status, reason, Timestamp.from(now), planId);
    }

    public void refreshCounts(long planId, int completed, int failed, int skipped, Instant now) {
        jdbc.update("""
                UPDATE batch_plan SET completed_count = ?, failed_count = ?, skipped_count = ?
                 WHERE id = ?
                """, completed, failed, skipped, planId);
    }

    public List<BatchPlan> listByPlayer(long playerId, int limit) {
        return jdbc.query(SELECT_COLS + " WHERE p.player_id = ? ORDER BY p.id DESC LIMIT ?",
                (rs, n) -> map(rs), playerId, limit);
    }

    public List<BatchPlan> listRecent(int limit) {
        return jdbc.query(SELECT_COLS + " ORDER BY p.id DESC LIMIT ?",
                (rs, n) -> map(rs), limit);
    }

    /** Reconciliation queue: plans that stopped claiming but never reached a terminal status. */
    public List<BatchPlan> listStuck(int limit) {
        return jdbc.query("""
                SELECT p.id AS id, p.plan_no AS plan_no, p.player_id AS player_id, p.recipe_id AS recipe_id,
                       p.recipe_version_id AS recipe_version_id, rv.version_no AS version_no,
                       p.total_units AS total_units, p.inputs_json AS inputs_json,
                       p.outputs_json AS outputs_json, p.status AS status, p.status_reason AS status_reason,
                       p.stop_new_units AS stop_new_units, p.cancel_requested AS cancel_requested,
                       p.completed_count AS completed_count, p.failed_count AS failed_count,
                       p.skipped_count AS skipped_count, p.created_at AS created_at, p.finished_at AS finished_at
                  FROM batch_plan p
                  JOIN recipe_version rv ON rv.id = p.recipe_version_id
                 WHERE p.status IN ('PENDING','RUNNING') AND p.stop_new_units = 1
                 ORDER BY p.id LIMIT ?
                """, (rs, n) -> map(rs), limit);
    }

    private static BatchPlan map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new BatchPlan(
                rs.getLong("id"), rs.getString("plan_no"), rs.getLong("player_id"),
                rs.getLong("recipe_id"), rs.getLong("recipe_version_id"),
                rs.getInt("version_no"),
                rs.getInt("total_units"), rs.getString("inputs_json"), rs.getString("outputs_json"),
                rs.getString("status"), rs.getString("status_reason"),
                rs.getInt("stop_new_units") == 1, rs.getInt("cancel_requested") == 1,
                rs.getInt("completed_count"), rs.getInt("failed_count"), rs.getInt("skipped_count"),
                created == null ? null : created.toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
