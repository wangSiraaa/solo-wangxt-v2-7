package com.gameops.craft.web;

import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.repo.RevokeRepository;
import com.gameops.craft.service.CraftService;
import com.gameops.craft.service.CraftTxService;
import com.gameops.craft.service.RecipeAdminService;
import com.gameops.craft.domain.ItemQty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    private final RecipeAdminService recipeAdmin;
    private final CraftTxService craftTx;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final LedgerRepository ledger;
    private final RevokeRepository revokes;
    private final Clock clock;

    public OperatorController(RecipeAdminService recipeAdmin, CraftTxService craftTx,
                              InventoryRepository inventory, OrderRepository orders,
                              LedgerRepository ledger, RevokeRepository revokes, Clock clock) {
        this.recipeAdmin = recipeAdmin;
        this.craftTx = craftTx;
        this.inventory = inventory;
        this.orders = orders;
        this.ledger = ledger;
        this.revokes = revokes;
        this.clock = clock;
    }

    public record CreateRecipeRequest(@NotBlank String code, @NotBlank String name) {}
    public record NewVersionRequest(@NotNull Long recipeId) {}
    public record EditDraftRequest(@NotNull Long versionId, @NotNull RecipeAdminService.VersionSpec spec) {}
    public record PublishRequest(@NotNull Long recipeId, @NotNull Long versionId) {}
    public record CloseRecipeRequest(@NotNull Long recipeId, String reason) {}
    public record RevokeRequest(@NotBlank String orderNo) {}
    public record GrantRequest(@NotNull Long playerId, @NotBlank String itemCode, @PositiveOrZero long qty) {}

    // ---- recipe lifecycle --------------------------------------------------

    @GetMapping("/recipes")
    public List<Map<String, Object>> listRecipes() {
        return recipeAdmin.listRecipesWithVersions();
    }

    @PostMapping("/recipes")
    public Map<String, Object> createRecipe(@Valid @RequestBody CreateRecipeRequest req,
                                            HttpServletRequest request) {
        return recipeAdmin.createRecipe(CurrentUsers.from(request).userId(), req.code(), req.name());
    }

    @PostMapping("/recipes/new-version")
    public Map<String, Object> newVersion(@Valid @RequestBody NewVersionRequest req) {
        return recipeAdmin.newVersion(req.recipeId());
    }

    @PostMapping("/recipes/draft")
    public Map<String, Object> editDraft(@Valid @RequestBody EditDraftRequest req) {
        recipeAdmin.editDraft(req.versionId(), req.spec());
        return Map.of("versionId", req.versionId(), "saved", true);
    }

    @PostMapping("/recipes/publish")
    public Map<String, Object> publish(@Valid @RequestBody PublishRequest req, HttpServletRequest request) {
        return recipeAdmin.publish(req.recipeId(), req.versionId(), CurrentUsers.from(request).userId());
    }

    @PostMapping("/recipes/close")
    public Map<String, Object> close(@Valid @RequestBody CloseRecipeRequest req) {
        recipeAdmin.close(req.recipeId(), req.reason());
        return Map.of("recipeId", req.recipeId(), "status", "CLOSED");
    }

    // ---- oversight ---------------------------------------------------------

    @GetMapping("/crafts")
    public List<Map<String, Object>> recentCrafts(@RequestParam(defaultValue = "100") int limit) {
        return orders.listRecent(Math.min(limit, 500)).stream().map(CraftService::orderBody).toList();
    }

    @GetMapping("/crafts/{orderNo}")
    public Map<String, Object> craftDetail(@PathVariable String orderNo) {
        var order = orders.findByNo(orderNo)
                .orElseThrow(() -> com.gameops.craft.common.ApiException
                        .notFound("ORDER_NOT_FOUND", "合成单不存在"));
        Map<String, Object> body = CraftService.orderBody(order);
        body.put("holds", ledger.listByRef(orderNo));
        return body;
    }

    @GetMapping("/ledger")
    public Object ledger(@RequestParam(required = false) String refNo,
                         @RequestParam(defaultValue = "100") int limit) {
        if (refNo != null && !refNo.isBlank()) {
            return ledger.listByRef(refNo);
        }
        return ledger.listRecent(Math.min(limit, 500));
    }

    /** Revoke a wrongly rewarded COMMITTED order; shortage goes to the exception list. */
    @PostMapping("/revokes")
    public Map<String, Object> revoke(@Valid @RequestBody RevokeRequest req, HttpServletRequest request) {
        CraftTxService.RevokeResult r =
                craftTx.revoke(req.orderNo(), CurrentUsers.from(request).userId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revokeNo", r.revokeNo());
        body.put("result", r.result());
        body.put("shortage", r.shortage());
        return body;
    }

    @GetMapping("/revokes")
    public List<Map<String, Object>> revokes(@RequestParam(required = false) String result) {
        List<RevokeRepository.RevokeRow> rows = result == null || result.isBlank()
                ? revokes.listRecent(200)
                : revokes.listByResult(result);
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revokeNo", r.revokeNo());
            m.put("orderNo", r.orderNo());
            m.put("playerId", r.playerId());
            m.put("result", r.result());
            m.put("shortage", CraftTxService.parseShortage(r.shortageJson()));
            m.put("operatorId", r.operatorId());
            m.put("createdAt", r.createdAt());
            return m;
        }).toList();
    }

    /** The exception queue: rewards that could not be clawed back because materials were used. */
    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions() {
        return revokes.listByResult("EXCEPTION").stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revokeNo", r.revokeNo());
            m.put("orderNo", r.orderNo());
            m.put("playerId", r.playerId());
            m.put("shortage", CraftTxService.parseShortage(r.shortageJson()));
            m.put("createdAt", r.createdAt());
            return m;
        }).toList();
    }

    /** Test/ops helper to set up player balances; writes an auditable GRANT ledger line. */
    @PostMapping("/inventory/grant")
    public Map<String, Object> grant(@Valid @RequestBody GrantRequest req) {
        Instant now = Instant.now(clock);
        inventory.upsertBalance(req.playerId(), req.itemCode(), req.qty(), now);
        String refNo = DocNumbers.next("GR", clock);
        ledger.insert(refNo, req.playerId(), req.itemCode(), "GRANT", req.qty(),
                null, "POSTED", "运营设置库存", now);
        return Map.of("refNo", refNo, "playerId", req.playerId(),
                "itemCode", req.itemCode(), "qty", req.qty());
    }
}
