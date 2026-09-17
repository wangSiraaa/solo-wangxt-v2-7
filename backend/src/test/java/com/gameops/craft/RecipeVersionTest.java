package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.domain.ItemQty;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.service.CraftService;
import com.gameops.craft.service.RecipeAdminService;
import com.gameops.craft.support.TestDataResetter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecipeVersionTest {

    @Autowired private RecipeAdminService admin;
    @Autowired private CraftService craft;
    @Autowired private InventoryRepository inventory;
    @Autowired private TestDataResetter resetter;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    @Test
    void published_version_is_immutable_and_new_version_replaces_it() {
        // v1 (id=1) is published -> editing must be rejected.
        RecipeAdminService.VersionSpec tamper = new RecipeAdminService.VersionSpec(
                List.of(new ItemQty("MAT_IRON", 1)),
                List.of(new ItemQty("EQP_FIRE_SWORD", 99)),
                null, null, 120);
        assertThatThrownBy(() -> admin.editDraft(1L, tamper))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VERSION_IMMUTABLE");

        var nv = admin.newVersion(1L);
        long draftId = ((Number) nv.get("draftVersionId")).longValue();
        assertThat(nv.get("versionNo")).isEqualTo(2);

        admin.editDraft(draftId, new RecipeAdminService.VersionSpec(
                List.of(new ItemQty("MAT_IRON", 5)),
                List.of(new ItemQty("EQP_FIRE_SWORD", 1), new ItemQty("GOLD", 500)),
                "2026-09-01T00:00:00Z", "2026-12-31T23:59:59Z", 120));
        admin.publish(1L, draftId, 1L);

        // New crafts now bind v2.
        var preview = craft.preview(4L, 1L);
        assertThat(preview.get("versionNo")).isEqualTo(2);
    }

    @Test
    void order_started_on_v1_finishes_with_v1_outputs_after_v2_published() {
        // player4 has enough for v1 (iron 3/core 2/shard 1)
        var pre = craft.preoccupy(4L, 2L, UUID.randomUUID().toString()); // THUNDER_BOW v1
        String orderNo = (String) pre.get("orderNo");
        assertThat(pre.get("boundVersionNo")).isEqualTo(1);

        // Meanwhile operations publishes a v2 with different output.
        var nv = admin.newVersion(2L);
        long draftId = ((Number) nv.get("draftVersionId")).longValue();
        admin.editDraft(draftId, new RecipeAdminService.VersionSpec(
                List.of(new ItemQty("MAT_WOOD", 4)),
                List.of(new ItemQty("EQP_THUNDER_BOW_PLUS", 1)),
                "2026-09-01T00:00:00Z", "2026-12-31T23:59:59Z", 120));
        admin.publish(2L, draftId, 1L);

        var done = craft.commit(4L, orderNo, UUID.randomUUID().toString());
        assertThat(done.get("boundVersionNo")).isEqualTo(1);
        assertThat(inventory.getQty(4L, "EQP_THUNDER_BOW")).isEqualTo(1);
        assertThat(inventory.getQty(4L, "EQP_THUNDER_BOW_PLUS")).isZero();
    }

    @Test
    void closed_recipe_is_rejected_by_server_even_if_client_force_submits() {
        // OLD_AMULET is CLOSED and its window ended 2026-08-15; server decides, not the button.
        assertThatThrownBy(() -> craft.preoccupy(4L, 3L, UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isIn("RECIPE_CLOSED", "ACTIVITY_NOT_OPEN");
    }
}
