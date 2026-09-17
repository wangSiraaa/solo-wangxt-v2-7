package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.service.CraftService;
import com.gameops.craft.service.CraftTxService;
import com.gameops.craft.support.TestDataResetter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LastMaterialConcurrencyTest {

    @Autowired private CraftService craftService;
    @Autowired private CraftTxService txService;
    @Autowired private InventoryRepository inventory;
    @Autowired private TestDataResetter resetter;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    private static final long PLAYER1 = 2L;
    private static final long RECIPE_FIRE_SWORD = 1L;

    @Test
    void exactly_one_of_two_concurrent_crafts_gets_the_last_materials() throws Exception {
        long ironBefore = inventory.getQty(PLAYER1, "MAT_IRON");
        long coreBefore = inventory.getQty(PLAYER1, "MAT_MAGIC_CORE");
        long shardBefore = inventory.getQty(PLAYER1, "MAT_FIRE_SHARD");
        assertThat(ironBefore).isEqualTo(3);
        assertThat(coreBefore).isEqualTo(2);
        assertThat(shardBefore).isEqualTo(1);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String key = UUID.randomUUID().toString();
            pool.submit(() -> {
                try {
                    start.await();
                    craftService.preoccupy(PLAYER1, RECIPE_FIRE_SWORD, key);
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if ("MATERIAL_INSUFFICIENT".equals(e.getCode())) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ok.get()).as("exactly one craft succeeds").isEqualTo(1);
        assertThat(rejected.get()).as("the other is rejected, not queued").isEqualTo(1);

        // Materials deducted once and only once.
        assertThat(inventory.getQty(PLAYER1, "MAT_IRON")).isZero();
        assertThat(inventory.getQty(PLAYER1, "MAT_MAGIC_CORE")).isZero();
        assertThat(inventory.getQty(PLAYER1, "MAT_FIRE_SHARD")).isZero();
    }

    @Test
    void same_player_retried_request_same_idempotency_key_does_not_double_deduct() {
        // player3 has 6 cores; two calls share ONE key -> one order, one deduction.
        String key = UUID.randomUUID().toString();
        var first = craftService.preoccupy(4L, RECIPE_FIRE_SWORD, key);
        var replay = craftService.preoccupy(4L, RECIPE_FIRE_SWORD, key);

        assertThat(replay.get("orderNo")).isEqualTo(first.get("orderNo"));
        assertThat(replay.get("replayed")).isEqualTo(true);
        // One craft's worth deducted (6 -> 3 cores, 9 -> 6 iron), not twice.
        assertThat(inventory.getQty(4L, "MAT_IRON")).isEqualTo(6);
        assertThat(inventory.getQty(4L, "MAT_MAGIC_CORE")).isEqualTo(4);
        assertThat(inventory.getQty(4L, "MAT_FIRE_SHARD")).isEqualTo(2);
    }

    @Test
    void two_parallel_calls_with_same_key_only_one_executes_the_other_is_rejected_in_flight()
            throws Exception {
        String key = UUID.randomUUID().toString();
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger doneOk = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        craftService.preoccupy(4L, RECIPE_FIRE_SWORD, key);
                        doneOk.incrementAndGet();
                    } catch (ApiException e) {
                        if ("RETRY_IN_FLIGHT".equals(e.getCode())
                                || "IDEMPOTENCY_RACE".equals(e.getCode())) {
                            inFlight.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(doneOk.get()).isEqualTo(1);
        assertThat(inFlight.get()).isEqualTo(1);
        // Still exactly one deduction.
        assertThat(inventory.getQty(4L, "MAT_IRON")).isEqualTo(6);
    }

    @Test
    void commit_retried_with_two_keys_issues_output_exactly_once() {
        // First key preoccupies. Two commit calls with DIFFERENT keys race the same order.
        var pre = craftService.preoccupy(4L, RECIPE_FIRE_SWORD, UUID.randomUUID().toString());
        String orderNo = (String) pre.get("orderNo");
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        craftService.commit(4L, orderNo, UUID.randomUUID().toString());
                        committed.incrementAndGet();
                    } catch (ApiException e) {
                        // A losing caller may hit an already-terminal order; both outcomes are safe.
                        if ("ORDER_NOT_OPEN".equals(e.getCode()) || "ORDER_COMMIT_RACE".equals(e.getCode())) {
                            refused.incrementAndGet();
                        } else {
                            unexpected.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        start.countDown();
        try {
            assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdown();

        assertThat(unexpected.get()).isZero();
        assertThat(committed.get() + refused.get()).isEqualTo(2);
        // Reward issued once, no matter how many retries arrived.
        assertThat(inventory.getQty(4L, "EQP_FIRE_SWORD")).isEqualTo(1);
        assertThat(inventory.getQty(4L, "GOLD")).isEqualTo(600);
    }
}
