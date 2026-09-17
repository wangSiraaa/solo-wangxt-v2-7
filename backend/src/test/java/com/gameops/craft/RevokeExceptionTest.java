package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.LedgerRepository;
import com.gameops.craft.service.CraftService;
import com.gameops.craft.service.CraftTxService;
import com.gameops.craft.support.TestDataResetter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Operator clawback:
 *  - full reversal posts negative REVOKE rows and takes the reward back;
 *  - when the player already spent part of it, nothing is deducted and the case
 *    lands on the exception queue with PENDING reverse rows;
 *  - double revoke is impossible (order state CAS + unique revoke per order).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RevokeExceptionTest {

    @Autowired private CraftService craft;
    @Autowired private CraftTxService tx;
    @Autowired private InventoryRepository inventory;
    @Autowired private LedgerRepository ledger;
    @Autowired private TestDataResetter resetter;

    @BeforeEach
    void resetData() {
        resetter.reset();
    }

    @Test
    void full_reverse_when_reward_still_held() {
        var pre = craft.preoccupy(4L, 1L, UUID.randomUUID().toString());
        String orderNo = (String) pre.get("orderNo");
        craft.commit(4L, orderNo, UUID.randomUUID().toString());
        assertThat(inventory.getQty(4L, "EQP_FIRE_SWORD")).isEqualTo(1);
        assertThat(inventory.getQty(4L, "GOLD")).isEqualTo(600);

        var r = tx.revoke(orderNo, 1L);
        assertThat(r.result()).isEqualTo("REVERSED");
        assertThat(inventory.getQty(4L, "EQP_FIRE_SWORD")).isZero();
        assertThat(inventory.getQty(4L, "GOLD")).isEqualTo(500);

        // Reverse trail exists and points back at the original order.
        var reversals = ledger.listByRef(r.revokeNo());
        assertThat(reversals).isNotEmpty();
        assertThat(reversals).allMatch(e -> "REVOKE".equals(e.entryType())
                && orderNo.equals(e.relatedRef()) && e.qtyDelta() < 0);

        // Second revoke is rejected, not applied twice.
        assertThatThrownBy(() -> tx.revoke(orderNo, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("ORDER_NOT_REVOKABLE");
    }

    @Test
    void reward_already_spent_goes_to_exception_list_without_partial_deduction() {
        var pre = craft.preoccupy(4L, 1L, UUID.randomUUID().toString());
        String orderNo = (String) pre.get("orderNo");
        craft.commit(4L, orderNo, UUID.randomUUID().toString());
        long swordBefore = inventory.getQty(4L, "EQP_FIRE_SWORD");
        long goldBefore = inventory.getQty(4L, "GOLD");

        // Player spends ALL 600 GOLD elsewhere (seed 500 + the 100 reward), so clawback is short.
        inventory.upsertBalance(4L, "GOLD", 0, java.time.Instant.now());

        var r = tx.revoke(orderNo, 1L);
        assertThat(r.result()).isEqualTo("EXCEPTION");
        assertThat(r.shortage()).hasSize(1);
        assertThat(r.shortage().get(0).get("itemCode")).isEqualTo("GOLD");

        // Balances untouched: no half-clawback; equipment was NOT taken either.
        assertThat(inventory.getQty(4L, "EQP_FIRE_SWORD")).isEqualTo(swordBefore);
        assertThat(inventory.getQty(4L, "GOLD")).isZero();

        // PENDING reverse rows document what is owed for manual settlement.
        var pending = ledger.listByRef(r.revokeNo());
        assertThat(pending).isNotEmpty();
        assertThat(pending).allMatch(e -> "PENDING".equals(e.status())
                && "REVOKE_PENDING".equals(e.entryType()));
    }
}
