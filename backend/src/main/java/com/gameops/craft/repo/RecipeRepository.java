package com.gameops.craft.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.RecipeVersion;
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
public class RecipeRepository {
    public record RecipeHeader(long id, String code, String name, String status) {}

    private final JdbcTemplate jdbc;

    public RecipeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecipeHeader> HEADER_MAPPER = (rs, n) -> new RecipeHeader(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("status"));

    public Long create(String code, String name, long operatorId, Instant now) {
        jdbc.update(
                "INSERT INTO recipe (code, name, status, created_by, created_at, updated_at) VALUES (?,?, 'ACTIVE', ?, ?, ?)",
                code, name, operatorId, Timestamp.from(now), Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM recipe WHERE code = ?", Long.class, code);
    }

    /** Row lock serialising publish/close against crafts that read the same recipe. */
    public RecipeHeader lockHeader(long recipeId) {
        List<RecipeHeader> rows = jdbc.query(
                "SELECT id, code, name, status FROM recipe WHERE id = ? FOR UPDATE",
                HEADER_MAPPER, recipeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<RecipeHeader> findHeader(long recipeId) {
        return jdbc.query("SELECT id, code, name, status FROM recipe WHERE id = ?", HEADER_MAPPER, recipeId)
                .stream().findFirst();
    }

    public Optional<RecipeHeader> findHeaderByCode(String code) {
        return jdbc.query("SELECT id, code, name, status FROM recipe WHERE code = ?", HEADER_MAPPER, code)
                .stream().findFirst();
    }

    public List<RecipeHeader> listHeaders() {
        return jdbc.query("SELECT id, code, name, status FROM recipe ORDER BY id", HEADER_MAPPER);
    }

    public void updateStatus(long recipeId, String status, Instant now) {
        jdbc.update("UPDATE recipe SET status = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.from(now), recipeId);
    }

    public Long insertDraft(long recipeId, int versionNo, Instant now) {
        jdbc.update("""
                INSERT INTO recipe_version
                  (recipe_id, version_no, status, inputs_json, outputs_json, start_time, end_time,
                   craft_timeout_s, published_at, published_by, created_at)
                VALUES (?, ?, 'DRAFT', NULL, NULL, NULL, NULL, 0, NULL, NULL, ?)
                """, recipeId, versionNo, Timestamp.from(now));
        return jdbc.queryForObject(
                "SELECT id FROM recipe_version WHERE recipe_id = ? AND version_no = ?",
                Long.class, recipeId, versionNo);
    }

    public int nextVersionNo(long recipeId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM recipe_version WHERE recipe_id = ?",
                Integer.class, recipeId);
        return (max == null ? 0 : max) + 1;
    }

    /** Draft content edit; published/archived rows are immutable and never match status='DRAFT'. */
    public int updateDraft(long versionId, String inputsJson, String outputsJson,
                           Instant startTime, Instant endTime, int timeoutSeconds) {
        return jdbc.update("""
                UPDATE recipe_version
                   SET inputs_json = ?, outputs_json = ?, start_time = ?, end_time = ?, craft_timeout_s = ?
                 WHERE id = ? AND status = 'DRAFT'
                """, inputsJson, outputsJson,
                startTime == null ? null : Timestamp.from(startTime),
                endTime == null ? null : Timestamp.from(endTime),
                timeoutSeconds, versionId);
    }

    public int publish(long versionId, long operatorId, Instant now) {
        return jdbc.update("""
                UPDATE recipe_version
                   SET status = 'PUBLISHED', published_at = ?, published_by = ?
                 WHERE id = ? AND status = 'DRAFT'
                """, Timestamp.from(now), operatorId, versionId);
    }

    public int archiveOtherPublished(long recipeId, long keepVersionId) {
        return jdbc.update(
                "UPDATE recipe_version SET status = 'ARCHIVED' WHERE recipe_id = ? AND status = 'PUBLISHED' AND id <> ?",
                recipeId, keepVersionId);
    }

    public Optional<RecipeVersion> findVersionById(long versionId) {
        return jdbc.query("SELECT * FROM recipe_version WHERE id = ?", VERSION_MAPPER, versionId)
                .stream().findFirst();
    }

    /** The version new crafts must bind to; null when the recipe has no live published version. */
    public Optional<RecipeVersion> findCurrentPublished(long recipeId) {
        return jdbc.query("""
                SELECT * FROM recipe_version
                 WHERE recipe_id = ? AND status = 'PUBLISHED'
                 ORDER BY version_no DESC LIMIT 1
                """, VERSION_MAPPER, recipeId).stream().findFirst();
    }

    public Optional<RecipeVersion> findPublishedVersionForUpdate(long recipeId) {
        return jdbc.query("""
                SELECT * FROM recipe_version
                 WHERE recipe_id = ? AND status = 'PUBLISHED'
                 ORDER BY version_no DESC LIMIT 1
                 FOR UPDATE
                """, VERSION_MAPPER, recipeId).stream().findFirst();
    }

    public List<RecipeVersion> listVersions(long recipeId) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_id = ? ORDER BY version_no DESC",
                VERSION_MAPPER, recipeId);
    }

    static List<ItemQty> parseItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Bad recipe item JSON: " + json, e);
        }
    }

    private static final RowMapper<RecipeVersion> VERSION_MAPPER = (rs, n) -> mapVersion(rs);

    private static RecipeVersion mapVersion(ResultSet rs) throws SQLException {
        Timestamp start = rs.getTimestamp("start_time");
        Timestamp end = rs.getTimestamp("end_time");
        Timestamp published = rs.getTimestamp("published_at");
        return new RecipeVersion(
                rs.getLong("id"),
                rs.getLong("recipe_id"),
                rs.getInt("version_no"),
                rs.getString("status"),
                parseItems(rs.getString("inputs_json")),
                parseItems(rs.getString("outputs_json")),
                start == null ? null : start.toInstant(),
                end == null ? null : end.toInstant(),
                rs.getInt("craft_timeout_s"),
                published == null ? null : published.toInstant());
    }
}
