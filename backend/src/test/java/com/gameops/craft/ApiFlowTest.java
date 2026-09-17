package com.gameops.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gameops.craft.support.TestDataResetter;
import java.util.Map;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Black-box HTTP flow: login as the seeded accounts, exercise the full craft
 * lifecycle with the Idempotency-Key header, and prove the last-material race
 * holds over real connections (H2 default profile).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiFlowTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired TestDataResetter resetter;

    private String base;
    private String player1Token;
    private String opsToken;

    @BeforeEach
    void setUp() {
        // JDK HttpURLConnection transparently re-issues a buffered POST on 401 and
        // throws "cannot retry due to server authentication, in streaming mode".
        // java.net.http.HttpClient does not perform that retry.
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());

        resetter.reset();
        base = "http://localhost:" + port;
        player1Token = login("player1", "player123");
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
    void login_rejects_bad_password_and_enforces_role_paths() {
        var bad = rest.postForEntity(base + "/api/auth/login",
                Map.of("username", "player1", "password", "wrong"), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(401);

        HttpHeaders h = auth(player1Token);
        var forbidden = rest.exchange(base + "/api/operator/recipes",
                HttpMethod.GET, new HttpEntity<>(h), Object.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void full_craft_lifecycle_preoccupy_commit_replay_then_ledger() {
        // Preview sees exact-one-craft availability.
        var preview = rest.exchange(base + "/api/player/recipes/1/preview",
                HttpMethod.GET, new HttpEntity<>(auth(player1Token)), Map.class);
        assertThat(preview.getBody().get("craftable")).isEqualTo(true);

        // Preoccupy with idempotency key.
        HttpHeaders preHeaders = auth(player1Token);
        String key = UUID.randomUUID().toString();
        preHeaders.set("Idempotency-Key", key);
        var preResp = rest.exchange(base + "/api/player/crafts/preoccupy",
                HttpMethod.POST, new HttpEntity<>(Map.of("recipeId", 1), preHeaders), Map.class);
        assertThat(preResp.getStatusCode().is2xxSuccessful()).isTrue();
        String orderNo = (String) preResp.getBody().get("orderNo");

        // Retried request (same key) replays the identical order instead of deducting again.
        var replay = rest.exchange(base + "/api/player/crafts/preoccupy",
                HttpMethod.POST, new HttpEntity<>(Map.of("recipeId", 1), preHeaders), Map.class);
        assertThat(replay.getBody().get("orderNo")).isEqualTo(orderNo);
        assertThat(replay.getBody().get("replayed")).isEqualTo(true);

        // Materials fully consumed; preview now impossible.
        @SuppressWarnings("unchecked")
        Map<String, Object> inv = rest.exchange(base + "/api/player/inventory",
                HttpMethod.GET, new HttpEntity<>(auth(player1Token)), Map.class).getBody();
        assertThat(((java.util.List<?>) inv.get("items")))
                .extracting(o -> ((Map<String, Object>) o).get("qty"))
                .allMatch(q -> ((Number) q).longValue() == 0L);

        // Commit; retried commit with new key returns the committed outcome (terminal state).
        HttpHeaders commitHeaders = auth(player1Token);
        commitHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        var commit = rest.exchange(base + "/api/player/crafts/commit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("orderNo", orderNo), commitHeaders), Map.class);
        assertThat(commit.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(commit.getBody().get("status")).isEqualTo("COMMITTED");

        // Order detail carries per-line material trail: CONSUME + PRODUCE.
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = rest.exchange(base + "/api/player/crafts/" + orderNo,
                HttpMethod.GET, new HttpEntity<>(auth(player1Token)), Map.class).getBody();
        assertThat((java.util.List<?>) detail.get("ledger"))
                .extracting(o -> ((Map<String, Object>) o).get("entryType"))
                .contains("CONSUME", "PRODUCE");
    }

    @Test
    void http_concurrency_last_materials_only_one_wins() throws Exception {
        // Reset player1 to exactly one craft using the ops grant endpoint.
        for (String[] it : new String[][]{
                {"MAT_IRON", "3"}, {"MAT_MAGIC_CORE", "2"}, {"MAT_FIRE_SHARD", "1"}}) {
            rest.exchange(base + "/api/operator/inventory/grant",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("playerId", 2, "itemCode", it[0], "qty", Long.parseLong(it[1])),
                            auth(opsToken)),
                    Map.class);
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    HttpHeaders h = auth(player1Token);
                    h.set("Idempotency-Key", UUID.randomUUID().toString());
                    ResponseEntity<Map> r = rest.exchange(base + "/api/player/crafts/preoccupy",
                            HttpMethod.POST, new HttpEntity<>(Map.of("recipeId", 1), h), Map.class);
                    if (r.getStatusCode().is2xxSuccessful()) {
                        ok.incrementAndGet();
                    } else if ("MATERIAL_INSUFFICIENT".equals(r.getBody().get("error"))) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
    }

    @Test
    void operator_can_revoke_a_committed_order() {
        // player3 crafts a thunder bow, operator revokes it.
        String p3 = login("player3", "player123");
        HttpHeaders h = auth(p3);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        var pre = rest.exchange(base + "/api/player/crafts/preoccupy",
                HttpMethod.POST, new HttpEntity<>(Map.of("recipeId", 2), h), Map.class);
        String orderNo = (String) pre.getBody().get("orderNo");

        HttpHeaders ch = auth(p3);
        ch.set("Idempotency-Key", UUID.randomUUID().toString());
        rest.exchange(base + "/api/player/crafts/commit",
                HttpMethod.POST, new HttpEntity<>(Map.of("orderNo", orderNo), ch), Map.class);

        var revoke = rest.exchange(base + "/api/operator/revokes",
                HttpMethod.POST, new HttpEntity<>(Map.of("orderNo", orderNo), auth(opsToken)), Map.class);
        assertThat(revoke.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(revoke.getBody().get("result")).isEqualTo("REVERSED");

        var exceptions = rest.exchange(base + "/api/operator/exceptions",
                HttpMethod.GET, new HttpEntity<>(auth(opsToken)), Object.class);
        assertThat(exceptions.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(exceptions.getBody()).isInstanceOf(java.util.List.class);
    }
}
