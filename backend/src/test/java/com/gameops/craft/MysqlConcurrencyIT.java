package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.repo.InventoryRepository;
import com.gameops.craft.service.CraftService;
import com.gameops.craft.support.MySQLIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Same last-material guarantee on real InnoDB (row locks + READ COMMITTED).
 * Requires Docker; run with: mvn test -Pmysql-it
 */
@SpringBootTest
@MySQLIT
class MysqlConcurrencyIT {

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

    @Autowired private CraftService craftService;
    @Autowired private InventoryRepository inventory;

    @Test
    void only_one_concurrent_craft_consumes_the_final_set_of_materials() throws Exception {
        int threads = 8;
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
                    craftService.preoccupy(2L, 1L, key);
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
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(inventory.getQty(2L, "MAT_IRON")).isZero();
        assertThat(inventory.getQty(2L, "MAT_MAGIC_CORE")).isZero();
        assertThat(inventory.getQty(2L, "MAT_FIRE_SHARD")).isZero();
    }
}
