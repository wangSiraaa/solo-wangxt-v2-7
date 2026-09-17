package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.repo.PlanRepository;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanUnitTxService;
import com.gameops.craft.support.MySQLIT;
import com.gameops.craft.support.PlanTestDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Batch-plan guarantees on real InnoDB (row locks, conditional UPDATEs, unique
 * keys). Requires Docker; run with: mvn test -Pmysql-it
 */
@SpringBootTest(properties = "app.plan.worker.enabled=false")
@MySQLIT
@TestMethodOrder(MethodOrderer.MethodName.class)
class MysqlBatchPlanIT {

    static MySQLContainer<?> mysql;

    @BeforeAll
    static void startDb() throws Exception {
        mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("craft")
                .withUsername("craft")
                .withPassword("craft");
        mysql.start();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema-mysql.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/data-seed-mysql.sql"));
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.datasource.hikari.transaction-isolation",
                () -> "TRANSACTION_READ_COMMITTED");
    }

    @Autowired private PlanService plans;
    @Autowired private PlanUnitTxService unitTx;
    @Autowired private PlanRepository planRepo;
    @Autowired private InventoryRepository inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanTestDriver driver;

    private static final long PLAYER3 = 4L;
    private static final long FIRE_SWORD = 1L;

    /** Reset only batch-plan-related rows; player4 is granted fresh materials. */
    private void cleanPlans() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("DELETE FROM craft_plan_repair");
        jdbc.update("DELETE FROM craft_plan_unit");
        jdbc.update("DELETE FROM craft_plan");
        jdbc.update("DELETE FROM ledger_entry WHERE plan_no IS NOT NULL");
        jdbc.update("DELETE FROM craft_order WHERE plan_no IS NOT NULL");
        jdbc.update("DELETE FROM idempotency_record WHERE scope IN ('PLAN_CREATE','PLAN_REPAIR')");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    private void grantSwordMaterials(long sets) {
        grant(PLAYER3, "MAT_IRON", 3 * sets);
        grant(PLAYER3, "MAT_MAGIC_CORE", 2 * sets);
        grant(PLAYER3, "MAT_FIRE_SHARD", sets);
        grant(PLAYER3, "EQP_FIRE_SWORD", 0);
    }

    private void grant(long player, String item, long qty) {
        inventory.upsertBalance(player, item, qty, Instant.now());
    }

    @Test
    void a_thirty_two_same_key_concurrent_creates_yield_one_plan_on_innodb() throws Exception {
        cleanPlans();
        grantSwordMaterials(5);
        String key = UUID.randomUUID().toString();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> nos = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Map<String, Object> b = plans.createPlan(PLAYER3, FIRE_SWORD, 5, key);
                    nos.add((String) b.get("planNo"));
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(nos).hasSize(32);
        assertThat(nos).containsOnly(nos.get(0));
        long planRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM craft_plan WHERE player_id = ?", Long.class, PLAYER3);
        assertThat(planRows).isEqualTo(1);
        long unitRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM craft_plan_unit WHERE plan_no = ?",
                Long.class, nos.get(0));
        assertThat(unitRows).isEqualTo(5);
    }

    @Test
    void b_two_workers_and_a_crash_window_finish_each_unit_once_on_innodb() throws Exception {
        cleanPlans();
        grantSwordMaterials(5);
        CraftPlan plan = planRepo.findByNo(
                (String) plans.createPlan(PLAYER3, FIRE_SWORD, 5, UUID.randomUUID().toString())
                        .get("planNo")).orElseThrow();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int w = 0; w < threads; w++) {
            final String worker = "w" + w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 5; i++) {
                        String token = UUID.randomUUID().toString();
                        var claim = driver.claim(plan, worker, token);
                        if (claim.unit() != null) {
                            driver.execute(plan, claim, worker, token);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        CraftPlan fin = planRepo.findByNo(plan.planNo()).orElseThrow();
        assertThat(fin.status()).isEqualTo("COMPLETED");
        assertThat(fin.completedCount()).isEqualTo(5);
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(5);

        // Simulate crash window 1 on a fresh plan: deduct then expire lease; resume.
        cleanPlans();
        grantSwordMaterials(1);
        CraftPlan p2 = planRepo.findByNo(
                (String) plans.createPlan(PLAYER3, FIRE_SWORD, 1, UUID.randomUUID().toString())
                        .get("planNo")).orElseThrow();
        String tA = UUID.randomUUID().toString();
        var claimA = driver.claim(p2, "deadA", tA);
        unitTx.deduct(claimA.unit().id(), tA);
        long unitId = claimA.unit().id();
        jdbc.update("UPDATE craft_plan_unit SET lease_expires_at = DATE_SUB(NOW(3), INTERVAL 60 SECOND) WHERE id = ?",
                unitId);
        String tB = UUID.randomUUID().toString();
        var claimB = driver.claim(p2, "recoverB", tB);
        assertThat(claimB.recovered()).isTrue();
        driver.execute(p2, claimB, "recoverB", tB);
        assertThat(planRepo.findByNo(p2.planNo()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(inventory.getQty(PLAYER3, "EQP_FIRE_SWORD")).isEqualTo(1);
    }
}
