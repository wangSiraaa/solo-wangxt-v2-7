package com.gameops.craft.repo;

import com.gameops.craft.domain.CraftPlan;
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
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT p.*, rv.version_no, r.code AS recipe_code, r.name AS recipe_name
              FROM craft_plan p
              JOIN recipe r ON r.id = p.recipe_id
              JOIN recipe_version rv ON rv.id = p.recipe_version_id
            """;

    private static final RowMapper<CraftPlan> MAPPER = (rs, n) -> map(rs);

    private static CraftPlan map(ResultSet rs) throws SQLException {
        return new CraftPlan(
                rs.getLong("id"),
                rs.getString("plan_no"),
                rs.getLong("player_id"),
                rs.getLong("recipe_id"),
                rs.getLong("recipe_version_id"),
                rs.getInt("version_no"),
                rs.getString("recipe_code"),
                rs.getString("recipe_name"),
                rs.getInt("total_count"),
                rs.getString("status"),
                rs.getInt("stop_flag") == 1,
                rs.getString("stop_reason"),
                rs.getInt("completed_count"),
                rs.getInt("skipped_count"),
                rs.getInt("failed_count"),
                rs.getString("inputs_json"),
                rs.getString("outputs_json"),
                instant(rs, "cancelled_at"),
                instant(rs, "finished_at"),
                instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }

    public long insert(String planNo, long playerId, long recipeId, long versionId, int count,
                       String inputsJson, String outputsJson, Instant now) {
        jdbc.update("""
                INSERT INTO craft_plan
                  (plan_no, player_id, recipe_id, recipe_version_id, total_count, status,
                   stop_flag, completed_count, skipped_count, failed_count,
                   inputs_json, outputs_json, created_at)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', 0, 0, 0, 0, ?, ?, ?)
                """, planNo, playerId, recipeId, versionId, count,
                inputsJson, outputsJson, Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM craft_plan WHERE plan_no = ?", Long.class, planNo);
    }

    public Optional<CraftPlan> findByNo(String planNo) {
        return jdbc.query(SELECT + " WHERE p.plan_no = ?", MAPPER, planNo)
                .stream().findFirst();
    }

    /** Row lock used by cancel / finalize / repair authorization checks. */
    public Optional<CraftPlan> findByNoForUpdate(String planNo) {
        return jdbc.query(SELECT + " WHERE p.plan_no = ? FOR UPDATE", MAPPER, planNo)
                .stream().findFirst();
    }

    public Optional<CraftPlan> findByIdForUpdate(long planId) {
        return jdbc.query(SELECT + " WHERE p.id = ? FOR UPDATE", MAPPER, planId)
                .stream().findFirst();
    }

    /**
     * Stop new units from starting. Never overwrites a terminal plan status and
     * never clears the flag once set; returns whether THIS call flipped it.
     */
    public int casRequestStop(long planId, String reason) {
        return jdbc.update("""
                UPDATE craft_plan
                   SET stop_flag = 1, stop_reason = COALESCE(stop_reason, ?)
                 WHERE id = ? AND stop_flag = 0 AND status = 'RUNNING'
                """, reason, planId);
    }

    /**
     * Player cancel. The plan keeps status RUNNING until the last leased unit
     * settles (so their completed counters still land), but stop_flag is set
     * immediately and the cancel decision recorded in stop_reason. The terminal
     * CANCELLED status is written by finalize once no unit is LEASED.
     */
    public int casCancel(long planId, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan
                   SET stop_flag = 1,
                       stop_reason = 'PLAYER_CANCELLED',
                       cancelled_at = ?
                 WHERE id = ? AND status = 'RUNNING' AND stop_flag = 0
                """, Timestamp.from(now), planId);
    }

    /** True once a player cancel has been recorded (cancelled_at set). */
    public boolean isCancelRequested(CraftPlan plan) {
        return plan.cancelledAt() != null;
    }

    /** Finalize: computed counters + terminal status, only out of RUNNING. */
    public int casFinalize(long planId, String status, int completed, int skipped,
                           int failed, Instant now) {
        return jdbc.update("""
                UPDATE craft_plan
                   SET status = ?, completed_count = ?, skipped_count = ?, failed_count = ?,
                       finished_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, status, completed, skipped, failed, Timestamp.from(now), planId);
    }

    public List<CraftPlan> listByPlayer(long playerId, int limit) {
        return jdbc.query(SELECT + " WHERE p.player_id = ? ORDER BY p.id DESC LIMIT ?",
                MAPPER, playerId, limit);
    }

    public List<CraftPlan> listRecent(int limit) {
        return jdbc.query(SELECT + " ORDER BY p.id DESC LIMIT ?", MAPPER, limit);
    }

    /** Operator anomaly view: non-completed plans and plans with failed units. */
    public List<CraftPlan> listAnomalous(int limit) {
        return jdbc.query(SELECT + " WHERE p.status <> 'COMPLETED' ORDER BY p.id DESC LIMIT ?",
                MAPPER, limit);
    }
}
