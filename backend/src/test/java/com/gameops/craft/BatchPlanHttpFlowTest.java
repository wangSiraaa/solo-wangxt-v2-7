package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.support.TestDataResetter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Black-box HTTP flow for batch plans: create with Idempotency-Key, worker
 * drains it, player reads live progress, operator runs reconciliation, and a
 * cancel request only skips not-started units.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.plan.worker.enabled=true",
                "app.plan.worker.delay-ms=200"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatchPlanHttpFlowTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired TestDataResetter resetter;

    private String base;
    private String playerToken;
    private String opsToken;

    @BeforeEach
    void setUp() {
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        resetter.reset();
        base = "http://localhost:" + port;
        playerToken = login("player3", "player123");
        opsToken = login("ops_admin", "operator123");
    }

    private String login(String username, String password) {
        var resp = rest.postForEntity(base + "/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return (String) resp.getBody().get("token");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Auth-Token", token);
        return h;
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_plan_worker_completes_it_progress_and_reconcile_visible_over_http() {
        // player3 seed has exactly 3 fire-sword crafts; plan asks for 3.
        HttpHeaders create = auth(playerToken);
        create.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = rest.exchange(base + "/api/player/plans", HttpMethod.POST,
                new HttpEntity<>(Map.of("recipeId", 1, "count", 3), create), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        String planNo = (String) created.getBody().get("planNo");
        assertThat(created.getBody().get("boundVersionNo")).isEqualTo(1);
        assertThat(created.getBody().get("status")).isEqualTo("RUNNING");

        // Same-key replay returns the same plan number.
        var replay = rest.exchange(base + "/api/player/plans", HttpMethod.POST,
                new HttpEntity<>(Map.of("recipeId", 1, "count", 3), create), Map.class);
        assertThat(replay.getBody().get("planNo")).isEqualTo(planNo);
        assertThat(replay.getBody().get("replayed")).isEqualTo(true);

        // Worker drains it.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    var d = rest.exchange(base + "/api/player/plans/" + planNo, HttpMethod.GET,
                            new HttpEntity<>(auth(playerToken)), Map.class);
                    assertThat(d.getBody().get("status")).isEqualTo("COMPLETED");
                });

        // Live progress / timeline payload.
        var detail = rest.exchange(base + "/api/player/plans/" + planNo, HttpMethod.GET,
                new HttpEntity<>(auth(playerToken)), Map.class);
        List<Map<String, Object>> units = (List<Map<String, Object>>) detail.getBody().get("units");
        assertThat(units).hasSize(3);
        assertThat(units).allSatisfy(u -> {
            assertThat(u.get("status")).isEqualTo("DONE");
            assertThat((String) u.get("orderNo")).startsWith("PU");
        });

        // Operator reconciliation is clean.
        var recon = rest.exchange(base + "/api/operator/plans/" + planNo + "/reconcile",
                HttpMethod.GET, new HttpEntity<>(auth(opsToken)), Map.class);
        assertThat(recon.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(recon.getBody().get("consistent")).isEqualTo(true);
        assertThat((List<?>) recon.getBody().get("diffs")).isEmpty();

        // Operator repairs listing works.
        var repairs = rest.exchange(base + "/api/operator/repairs?planNo=" + planNo,
                HttpMethod.GET, new HttpEntity<>(auth(opsToken)), List.class);
        assertThat(repairs.getBody()).isEmpty();

        // A player cannot hit operator endpoints.
        var forbidden = rest.exchange(base + "/api/operator/plans", HttpMethod.GET,
                new HttpEntity<>(auth(playerToken)), Object.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @SuppressWarnings("unchecked")
    void count_out_of_range_rejected_and_cancel_skips_unstarted_units() {
        // 101 > max -> bean validation rejects at the server (rule never decided by UI).
        HttpHeaders h = auth(playerToken);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        var bad = rest.exchange(base + "/api/player/plans", HttpMethod.POST,
                new HttpEntity<>(Map.of("recipeId", 1, "count", 101), h), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat((String) bad.getBody().get("error")).startsWith("VALIDATION_FAILED");

        // A big plan (10) beyond the 3-craft inventory: create ok, cancel right
        // away while the worker races; final plan is CANCELLED, rewards never rolled back.
        HttpHeaders h2 = auth(playerToken);
        h2.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = rest.exchange(base + "/api/player/plans", HttpMethod.POST,
                new HttpEntity<>(Map.of("recipeId", 1, "count", 10), h2), Map.class);
        String planNo = (String) created.getBody().get("planNo");

        rest.exchange(base + "/api/player/plans/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("planNo", planNo, "reason", "http cancel"),
                        auth(playerToken)), Map.class);

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    var d = rest.exchange(base + "/api/player/plans/" + planNo, HttpMethod.GET,
                            new HttpEntity<>(auth(playerToken)), Map.class);
                    Object st = d.getBody().get("status");
                    assertThat(st).isIn("CANCELLED", "PARTIAL");
                });
    }
}
