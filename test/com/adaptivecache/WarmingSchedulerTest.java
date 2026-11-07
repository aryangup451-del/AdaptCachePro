package com.adaptivecache;

import com.adaptivecache.core.LRUCache;
import com.adaptivecache.predict.EwmaPredictor;
import com.adaptivecache.stats.RequestStatsRegistry;
import com.adaptivecache.stats.RequestStats.Outcome;
import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.warming.WarmingScheduler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Warming scheduler: pre-fetches top-known keys from an origin into an
 * empty cache.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarmingSchedulerTest {

    private HttpServer origin;

    @BeforeAll
    void startOrigin() throws IOException {
        origin = HttpServer.create(new InetSocketAddress(0), 0);
        origin.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        origin.createContext("/", exchange -> {
            byte[] body = ("{\"warm\":true,\"path\":\""
                    + exchange.getRequestURI().getPath() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    "application/json");
            exchange.getResponseHeaders().set("ETag", "\"w1\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        origin.start();
    }

    @AfterAll
    void stopOrigin() {
        origin.stop(0);
    }

    @Test
    void warmsTopKeysIntoEmptyCache() {
        LRUCache cache = new LRUCache(32);
        RequestStatsRegistry stats = new RequestStatsRegistry();
        for (int i = 0; i < 5; i++) {
            stats.record("GET /hot/a", Outcome.MISS,
                    TrafficClass.HOT, 3, 100, false);
        }
        for (int i = 0; i < 3; i++) {
            stats.record("GET /hot/b", Outcome.MISS,
                    TrafficClass.HOT, 3, 100, false);
        }
        EwmaPredictor predictor = new EwmaPredictor();
        String base = "http://localhost:"
                + origin.getAddress().getPort();
        WarmingScheduler scheduler = new WarmingScheduler(cache, predictor,
                stats, base, 2000);

        WarmingScheduler.WarmResult result = scheduler.warmTopK(10, 60L);

        assertTrue(result.attempted() >= 1);
        assertTrue(result.stored() >= 1);
        assertTrue(cache.size() >= 1);
        assertTrue(scheduler.lastWarmedCount() >= 1);
    }
}
