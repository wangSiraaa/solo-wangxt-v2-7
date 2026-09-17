package com.gameops.craft.web;

import com.gameops.craft.repo.HoldRepository;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.repo.OrderRepository;
import com.gameops.craft.service.CraftService;
import com.gameops.craft.service.CraftTxService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/player")
public class PlayerController {

    private final CraftService craft;
    private final CraftTxService tx;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final LedgerRepository ledger;
    private final HoldRepository holds;

    public PlayerController(CraftService craft, CraftTxService tx, InventoryRepository inventory,
                            OrderRepository orders, LedgerRepository ledger, HoldRepository holds) {
        this.craft = craft;
        this.tx = tx;
        this.inventory = inventory;
        this.orders = orders;
        this.ledger = ledger;
        this.holds = holds;
    }

    public record PreoccupyRequest(@NotNull Long recipeId) {}
    public record CommitRequest(@NotBlank String orderNo) {}
    public record CancelRequest(@NotBlank String orderNo, String reason) {}

    @GetMapping("/recipes")
    public List<Map<String, Object>> recipeCatalog(HttpServletRequest request) {
        long playerId = CurrentUsers.from(request).userId();
        return craft.catalog(playerId);
    }

    @GetMapping("/recipes/{recipeId}/preview")
    public Map<String, Object> preview(@PathVariable long recipeId, HttpServletRequest request) {
        return craft.preview(CurrentUsers.from(request).userId(), recipeId);
    }

    @PostMapping("/crafts/preoccupy")
    public Map<String, Object> preoccupy(@Valid @RequestBody PreoccupyRequest req,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         HttpServletRequest request) {
        return craft.preoccupy(CurrentUsers.from(request).userId(), req.recipeId(), key);
    }

    @PostMapping("/crafts/commit")
    public Map<String, Object> commit(@Valid @RequestBody CommitRequest req,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      HttpServletRequest request) {
        return craft.commit(CurrentUsers.from(request).userId(), req.orderNo(), key);
    }

    @PostMapping("/crafts/cancel")
    public Map<String, Object> cancel(@Valid @RequestBody CancelRequest req, HttpServletRequest request) {
        return craft.cancel(CurrentUsers.from(request).userId(), req.orderNo(), req.reason());
    }

    @GetMapping("/crafts")
    public List<Map<String, Object>> myCrafts(HttpServletRequest request) {
        long playerId = CurrentUsers.from(request).userId();
        return orders.listByPlayer(playerId, 100).stream().map(CraftService::orderBody).toList();
    }

    @GetMapping("/crafts/{orderNo}")
    public Map<String, Object> craftDetail(@PathVariable String orderNo, HttpServletRequest request) {
        return craft.orderDetail(CurrentUsers.from(request).userId(), orderNo);
    }

    @GetMapping("/inventory")
    public Map<String, Object> myInventory(HttpServletRequest request) {
        long playerId = CurrentUsers.from(request).userId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", inventory.list(playerId));
        body.put("activeHolds", holds.listByPlayer(playerId));
        return body;
    }

    @GetMapping("/ledger")
    public Object myLedger(@RequestParam(required = false) String orderNo,
                           @RequestParam(defaultValue = "100") int limit,
                           HttpServletRequest request) {
        long playerId = CurrentUsers.from(request).userId();
        if (orderNo != null && !orderNo.isBlank()) {
            return craft.orderDetail(playerId, orderNo);
        }
        return ledger.listByPlayer(playerId, Math.min(limit, 500));
    }
}
