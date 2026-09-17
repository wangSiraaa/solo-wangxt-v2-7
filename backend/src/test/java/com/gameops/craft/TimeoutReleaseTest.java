package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.service.CraftService;
import com.gameops.craft.support.TestDataResetter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Timeout release driven by a controllable clock: holds are returned once, never twice. */
@SpringBootTest
@TestPropertySource(properties = "app.sweeper.delay-ms=60000") // don't let the real sweeper interfere
class TimeoutReleaseTest {

    private static final Instant T = Instant.parse("2026-09-17T10:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        static java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(T);
        @Bean @Primary
        Clock testClock() {
            return new Clock() {
                public Instant instant() { return now.get(); }
                public ZoneOffset getZone() { return ZoneOffset.UTC; }
                public Clock withZone(java.time.ZoneId zone) { return this; }
            };
        }
    }

    @Autowired private CraftService craftService;
    @Autowired private Clock clock;
    @Autowired private InventoryRepository inventory;
    @Autowired private TestDataResetter resetter;

    @BeforeEach
    void resetData() {
        resetter.reset();
        FixedClockConfig.now.set(T);
    }

    @Test
    void expired_preoccupy_releases_everything_and_late_commit_gets_no_reward() {
        long ironBefore = inventory.getQty(4L, "MAT_IRON");
        var pre = craftService.preoccupy(4L, 1L, UUID.randomUUID().toString());
        String orderNo = (String) pre.get("orderNo");

        // Materials gone while order is open.
        assertThat(inventory.getQty(4L, "MAT_IRON")).isEqualTo(ironBefore - 3);

        // Advance past the 120s preoccupy window and run timeout logic.
        FixedClockConfig.now.set(T.plusSeconds(200));
        // timeout via the same facade path sweeper uses (tx service invoked through service ctx)
        craftService.forceTimeout(orderNo);

        // Everything returned, exactly once.
        assertThat(inventory.getQty(4L, "MAT_IRON")).isEqualTo(ironBefore);
        assertThat(inventory.getQty(4L, "MAT_MAGIC_CORE")).isEqualTo(6);
        assertThat(inventory.getQty(4L, "MAT_FIRE_SHARD")).isEqualTo(3);

        // Late commit cannot issue the reward.
        String commitKey = UUID.randomUUID().toString();
        assertThatThrownBy(() -> craftService.commit(4L, orderNo, commitKey))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .satisfiesAnyOf(c -> assertThat(c).isIn("ORDER_NOT_OPEN", "PREOCCUPY_EXPIRED"));
        assertThat(inventory.getQty(4L, "EQP_FIRE_SWORD")).isZero();
    }
}
