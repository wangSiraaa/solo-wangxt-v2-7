package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.support.TestDataResetter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end batch-plan execution through the REAL scheduled PlanWorker
 * (enabled here): after creation the worker leases units from MySQL/H2 and
 * drives deduct -> reward -> writeback itself; the client never loops.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.plan.worker.enabled=true",
        "app.plan.worker.delay-ms=200",
        "app.plan.lease-seconds=5"
})
class BatchPlanWorkerE2ETest {

    private static final long PLAYER3 = 4L;
    private static final long FIRE_SWORD = 1L;

    @Autowired private PlanService plans;
    @Autowired private PlanRepository planRepo;
    @Autowired private InventoryRepository inventory;
    @Autowired private TestDataResetter resetter;

    @BeforeEach
    void reset() {
        resetter.reset();
    }

    @Test
    void scheduled_worker_drains_a_plan_to_completed_without_client_calls() {
        inventory.upsertBalance(PLAYER3, "MAT_IRON", 9, Instant.now());
        inventory.upsertBalance(PLAYER3, "MAT_MAGIC_CORE", 6, Instant.now());
        inventory.upsertBalance(PLAYER3, "MAT_FIRE_SHARD", 3, Instant.now());

        Map<String, Object> created =
                plans.createPlan(PLAYER3, FIRE_SWORD, 3, UUID.randomUUID().toString());
        String planNo = (String) created.get("planNo");

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    CraftPlan p = planRepo.findByNo(planNo).orElseThrow();
                    assertThat(p.status()).isEqualTo("COMPLETED");
                    assertThat(p.completedCount()).isEqualTo(3);
                });

        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(3);
        assertThat(inventory.getQty(PLAYER3, "MAT_IRON")).isZero();
    }
}
