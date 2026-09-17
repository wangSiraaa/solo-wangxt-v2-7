package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.Json;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.domain.RecipeVersion;
import com.gameops.craft.repo.RecipeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recipe publishing rules:
 *  - a PUBLISHED version is immutable (inputs/outputs/window cannot be edited);
 *  - changing a live recipe means: new version (DRAFT) -> edit -> publish,
 *    which atomically ARCHIVES the previous published version;
 *  - crafts already started keep the version id snapshot on their order and finish on it.
 */
@Service
public class RecipeAdminService {

    private final RecipeRepository recipes;
    private final Clock clock;

    public RecipeAdminService(RecipeRepository recipes, Clock clock) {
        this.recipes = recipes;
        this.clock = clock;
    }

    public record RecipeSpec(String name, String code) {}
    public record VersionSpec(List<ItemQty> inputs, List<ItemQty> outputs,
                              String startTime, String endTime, Integer craftTimeoutSeconds) {}

    @Transactional
    public Map<String, Object> createRecipe(long operatorId, String code, String name) {
        if (recipes.findHeaderByCode(code).isPresent()) {
            throw ApiException.conflict("RECIPE_CODE_EXISTS", "配方编码已存在：" + code);
        }
        Instant now = Instant.now(clock);
        long id = recipes.create(code, name, operatorId, now);
        int versionNo = recipes.nextVersionNo(id);
        long draftId = recipes.insertDraft(id, versionNo, now);
        return Map.of("recipeId", id, "draftVersionId", draftId, "versionNo", versionNo);
    }

    /** Start a new editable version from the recipe's current content. */
    @Transactional
    public Map<String, Object> newVersion(long recipeId) {
        Instant now = Instant.now(clock);
        RecipeRepository.RecipeHeader header = lockRecipe(recipeId);
        int versionNo = recipes.nextVersionNo(recipeId);
        long draftId = recipes.insertDraft(recipeId, versionNo, now);
        return Map.of("recipeId", header.id(), "draftVersionId", draftId, "versionNo", versionNo);
    }

    @Transactional
    public void editDraft(long versionId, VersionSpec spec) {
        RecipeVersion v = recipes.findVersionById(versionId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "版本不存在"));
        if (!"DRAFT".equals(v.status())) {
            throw ApiException.conflict("VERSION_IMMUTABLE",
                    "已发布版本不可修改，请先新建版本（当前状态：" + v.status() + "）");
        }
        validate(spec);
        try {
            int changed = recipes.updateDraft(versionId,
                    Json.MAPPER.writeValueAsString(spec.inputs()),
                    Json.MAPPER.writeValueAsString(spec.outputs()),
                    spec.startTime() == null || spec.startTime().isBlank() ? null : Instant.parse(spec.startTime()),
                    spec.endTime() == null || spec.endTime().isBlank() ? null : Instant.parse(spec.endTime()),
                    spec.craftTimeoutSeconds());
            if (changed == 0) {
                throw ApiException.conflict("VERSION_NOT_DRAFT", "版本不是草稿，无法修改");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_SPEC", "配方内容格式错误：" + e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> publish(long recipeId, long versionId, long operatorId) {
        Instant now = Instant.now(clock);
        RecipeRepository.RecipeHeader header = lockRecipe(recipeId);
        RecipeVersion v = recipes.findVersionById(versionId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "版本不存在"));
        if (v.recipeId() != recipeId) {
            throw ApiException.badRequest("VERSION_RECIPE_MISMATCH", "版本不属于该配方");
        }
        if (!"DRAFT".equals(v.status())) {
            throw ApiException.conflict("VERSION_NOT_DRAFT", "仅草稿版本可发布");
        }
        if (v.inputs().isEmpty() || v.outputs().isEmpty() || v.craftTimeoutSeconds() <= 0) {
            throw ApiException.badRequest("VERSION_INCOMPLETE", "请先补全材料、产出与超时时间");
        }
        // Atomic swap: new PUBLISHED wins, old PUBLISHED -> ARCHIVED in the same transaction.
        int published = recipes.publish(versionId, operatorId, now);
        if (published == 0) {
            throw ApiException.conflict("VERSION_PUBLISH_RACE", "发布冲突，请重试");
        }
        recipes.archiveOtherPublished(recipeId, versionId);
        if ("CLOSED".equals(header.status())) {
            recipes.updateStatus(recipeId, "ACTIVE", now);
        }
        return Map.of("recipeId", recipeId, "versionId", versionId,
                "versionNo", v.versionNo(), "status", "PUBLISHED");
    }

    @Transactional
    public void close(long recipeId, String reason) {
        lockRecipe(recipeId);
        recipes.updateStatus(recipeId, "CLOSED", Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRecipesWithVersions() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (RecipeRepository.RecipeHeader h : recipes.listHeaders()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recipeId", h.id());
            m.put("code", h.code());
            m.put("name", h.name());
            m.put("status", h.status());
            m.put("versions", recipes.listVersions(h.id()).stream().map(RecipeAdminService::versionBody).toList());
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> versionBody(RecipeVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", v.id());
        m.put("versionNo", v.versionNo());
        m.put("status", v.status());
        m.put("inputs", v.inputs());
        m.put("outputs", v.outputs());
        m.put("startTime", v.startTime());
        m.put("endTime", v.endTime());
        m.put("craftTimeoutSeconds", v.craftTimeoutSeconds());
        m.put("publishedAt", v.publishedAt());
        return m;
    }

    private RecipeRepository.RecipeHeader lockRecipe(long recipeId) {
        RecipeRepository.RecipeHeader header = recipes.lockHeader(recipeId);
        if (header == null) {
            throw ApiException.notFound("RECIPE_NOT_FOUND", "配方不存在");
        }
        return header;
    }

    private static void validate(VersionSpec spec) {
        if (spec.inputs() == null || spec.inputs().isEmpty()) {
            throw ApiException.badRequest("INPUTS_REQUIRED", "至少需要一种材料");
        }
        if (spec.outputs() == null || spec.outputs().isEmpty()) {
            throw ApiException.badRequest("OUTPUTS_REQUIRED", "至少需要一种产出");
        }
        if (spec.craftTimeoutSeconds() == null || spec.craftTimeoutSeconds() < 10) {
            throw ApiException.badRequest("TIMEOUT_TOO_SHORT", "预占超时不得少于 10 秒");
        }
        for (ItemQty i : spec.inputs()) {
            if (i.getItemCode() == null || i.getItemCode().isBlank() || i.getQty() <= 0) {
                throw ApiException.badRequest("BAD_INPUT_LINE", "材料行非法");
            }
        }
        for (ItemQty o : spec.outputs()) {
            if (o.getItemCode() == null || o.getItemCode().isBlank() || o.getQty() <= 0) {
                throw ApiException.badRequest("BAD_OUTPUT_LINE", "产出行非法");
            }
        }
    }
}
