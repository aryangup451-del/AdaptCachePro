package com.adaptivecache;

import com.adaptivecache.classifier.TrafficClassifier;
import com.adaptivecache.origin.ChaosController;
import com.adaptivecache.origin.OriginServer;
import com.adaptivecache.policy.AdaptivePolicyEngine;
import com.adaptivecache.proxy.ProxyServer;
import com.adaptivecache.stats.CostModel;
import com.adaptivecache.stats.RequestStatsRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration: origin + proxy on ephemeral ports, exercising the
 * full four-phase pipeline — hit/miss, classification, adaptive TTLs, RFC
 * 5861 SWR, stale-if-error and chaos degradation.
 *
 * <p>Tiny TTLs (1s) make expiry-based behaviour fast and deterministic.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyIntegrationTest {

    private OriginServer origin;
    private ProxyServer proxy;
    private HttpClient client;
    private String proxyBase;

    @BeforeAll
    void startServers() throws IOException {
        origin = new OriginServer(0).start();

        // TTL: hot=1s, bursty=1s, one-shot=1s; SWR window 30s.
        AdaptivePolicyEngine policy =
                new AdaptivePolicyEngine(1, 1, 1, 30, 3600);

        proxy = new ProxyServer(0, "http://localhost:" + origin.port(),
                128, policy, new TrafficClassifier(),
                new RequestStatsRegistry(1000), new CostModel(), 2000);
        proxy.start();
        proxyBase = "http://localhost:" + proxy.port();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterAll
    void stopServers() {
        proxy.stop();
        origin.stop();
    }

    private HttpResponse<String> get(String path, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(proxyBase + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------

    @Test
    void missThenHit() throws Exception {
        HttpResponse<String> first = get("/hot/item-1");
        HttpResponse<String> second = get("/hot/item-1");

        assertEquals(200, first.statusCode());
        assertEquals("MISS", first.headers().firstValue("X-Cache").orElse(""));
        assertEquals(200, second.statusCode());
        assertEquals("HIT", second.headers().firstValue("X-Cache").orElse(""));
        assertEquals(first.body(), second.body());
    }

    @Test
    void conditionalRequestReturns304() throws Exception {
        get("/hot/item-2");
        HttpResponse<String> resp = get("/hot/item-2");
        String etag = resp.headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> notModified =
                get("/hot/item-2", "If-None-Match", etag);
        assertEquals(304, notModified.statusCode());
        assertEquals("REVALIDATED",
                notModified.headers().firstValue("X-Cache").orElse(""));
    }

    @Test
    void repeatedHitsPromoteToHotClass() throws Exception {
        get("/hot/item-3");
        get("/hot/item-3");
        get("/hot/item-3");
        HttpResponse<String> resp = get("/hot/item-3");
        assertEquals("HOT",
                resp.headers().firstValue("X-Traffic-Class").orElse(""));
        String cacheControl = resp.headers()
                .firstValue("Cache-Control").orElse("");
        assertTrue(cacheControl.contains("public"));
    }

    @Test
    void oneShotKeysAreClassifiedOneShotInitially() throws Exception {
        HttpResponse<String> resp = get("/oneshot/fresh-key-xyz");
        assertEquals("MISS", resp.headers().firstValue("X-Cache").orElse(""));
        assertEquals("ONE_SHOT",
                resp.headers().firstValue("X-Traffic-Class").orElse(""));
        assertTrue(resp.headers().firstValue("Cache-Control").orElse("")
                .contains("max-age=1"));
    }

    @Test
    void staleWhileRevalidateServesStaleAndRevalidates() throws Exception {
        // Bursty pattern: two clusters separated by a gap > TTL.
        get("/bursty/swr-key-1");
        get("/bursty/swr-key-1");
        get("/bursty/swr-key-1");
        Thread.sleep(1_200);                     // expire the 1s TTL
        get("/bursty/swr-key-1");
        get("/bursty/swr-key-1");
        Thread.sleep(1_200);                     // expire again
        HttpResponse<String> stale = get("/bursty/swr-key-1");

        assertEquals(200, stale.statusCode());
        assertEquals("STALE", stale.headers().firstValue("X-Cache").orElse(""));
        assertTrue(stale.headers().firstValue("Warning").orElse("")
                .contains("110"));
        assertTrue(stale.headers().firstValue("Cache-Control").orElse("")
                .contains("stale-while-revalidate=30"));

        // Give the async revalidation a moment to complete.
        Thread.sleep(800);
        HttpResponse<String> after = get("/bursty/swr-key-1");
        assertNotEquals("ERROR", after.headers().firstValue("X-Cache")
                .orElse("ERROR"));
    }

    @Test
    void staleIfErrorServesExpiredEntryWhenOriginDown() throws Exception {
        get("/static/sie-key-1");
        Thread.sleep(1_200);                     // expire

        origin.chaos().applyMode(ChaosController.Mode.DOWN);
        try {
            HttpResponse<String> resp = get("/static/sie-key-1");
            assertEquals(200, resp.statusCode());
            assertEquals("STALE",
                    resp.headers().firstValue("X-Cache").orElse(""));
            assertTrue(resp.headers().firstValue("Warning").orElse("")
                    .contains("111"));
        } finally {
            origin.chaos().applyMode(ChaosController.Mode.CLEAN);
        }
    }

    @Test
    void unknownKeyWithOriginDownYields503() throws Exception {
        origin.chaos().applyMode(ChaosController.Mode.DOWN);
        try {
            HttpResponse<String> resp = get("/static/never-seen-before");
            assertEquals(503, resp.statusCode());
            assertEquals("ERROR",
                    resp.headers().firstValue("X-Cache").orElse(""));
        } finally {
            origin.chaos().applyMode(ChaosController.Mode.CLEAN);
        }
    }

    @Test
    void chaosFailuresDoNotBreakCachedKeys() throws Exception {
        get("/hot/chaos-key-1");
        origin.chaos().applyMode(ChaosController.Mode.DOWN);
        try {
            // Cached key stays served from cache even with origin down.
            HttpResponse<String> resp = get("/hot/chaos-key-1");
            assertEquals(200, resp.statusCode());
            assertEquals("HIT", resp.headers().firstValue("X-Cache")
                    .orElse(""));
        } finally {
            origin.chaos().applyMode(ChaosController.Mode.CLEAN);
        }
    }

    @Test
    void telemetryEndpointsExposeStats() throws Exception {
        get("/hot/telemetry-key-1");

        HttpResponse<String> health = get("/_health");
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("OK"));

        HttpResponse<String> stats = get("/stats");
        assertEquals(200, stats.statusCode());
        assertTrue(stats.body().contains("AdaptCache Pro"));

        HttpResponse<String> metrics = get("/metrics");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("adaptcache_total_requests"));
        assertTrue(metrics.body().contains("adaptcache_hit_ratio"));
    }

    @Test
    void clientErrorsPassThroughVerbatim() throws Exception {
        HttpResponse<String> resp = get("/missing/nope");
        assertEquals(404, resp.statusCode());
        assertEquals("BYPASS",
                resp.headers().firstValue("X-Cache").orElse(""));
    }

    @Test
    void costModelAccumulatesSavings() throws Exception {
        for (int i = 0; i < 5; i++) {
            get("/hot/cost-key-1");
        }
        assertTrue(proxy.costModel().report(
                proxy.stats().totalRequests(),
                proxy.stats().overallHitRatio()).exactSavingsUsd() > 0);
        assertTrue(proxy.stats().totalRequests() > 0);
        assertTrue(proxy.stats().overallHitRatio() > 0);
    }
}
