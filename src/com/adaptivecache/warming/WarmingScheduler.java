package com.adaptivecache.warming;

import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.predict.EwmaPredictor;
import com.adaptivecache.stats.RequestStats;
import com.adaptivecache.stats.RequestStatsRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Predictive cache warming (Item 6). Pre-fetches the top predicted keys
 * from the origin into the cache on boot and on demand, so expected
 * traffic finds a warm cache instead of paying cold misses.
 */
public final class WarmingScheduler {

    private final LRUCache cache;
    private final EwmaPredictor predictor;
    private final RequestStatsRegistry stats;
    private final HttpClient client;
    private final String originBaseUrl;
    private final long timeoutMs;

    private volatile long lastWarmed = 0;
    private volatile int lastWarmedCount = 0;

    public WarmingScheduler(LRUCache cache, EwmaPredictor predictor,
                            RequestStatsRegistry stats, String originBaseUrl,
                            long timeoutMs) {
        this.cache = cache;
        this.predictor = predictor;
        this.stats = stats;
        this.originBaseUrl = originBaseUrl;
        this.timeoutMs = timeoutMs;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public record WarmResult(int attempted, int stored, long millis) {
    }

    public WarmResult warmTopK(int topK, long predictorHorizonSeconds) {
        long start = System.currentTimeMillis();
        List<RequestStats> ranked = stats.snapshot().stream()
                .sorted(Comparator.comparingLong(
                        RequestStats::getRequestCount).reversed())
                .limit(Math.max(1, topK))
                .toList();

        int attempted = 0;
        int stored = 0;
        for (RequestStats s : ranked) {
            String key = s.getKey();
            if (cache.peek(canonicalPath(key)) != null) {
                continue;
            }
            double predicted = predictor.predict(key,
                    predictorHorizonSeconds, 100.0);
            if (predicted <= 0 && s.getRequestCount() < 2) {
                continue;
            }
            attempted++;
            if (fetchAndStore(key)) {
                stored++;
            }
        }
        lastWarmed = System.currentTimeMillis();
        lastWarmedCount = stored;
        return new WarmResult(attempted, stored, lastWarmed - start);
    }

    private boolean fetchAndStore(String key) {
        try {
            String path = canonicalPath(key);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(originBaseUrl + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                byte[] body = resp.body();
                CacheEntry entry = new CacheEntry(key,
                        key.hashCode(), body);
                entry.setEtag(resp.headers()
                        .firstValue("ETag").orElse(null));
                entry.setContentType(resp.headers()
                        .firstValue("Content-Type")
                        .orElse("application/octet-stream"));
                long now = System.currentTimeMillis();
                entry.setStoredAt(now);
                entry.applyPolicy(60_000L, 0L, 3_600_000L,
                        com.adaptivecache.classifier.TrafficClass.BURSTY,
                        now);
                return cache.put(key, entry);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String canonicalPath(String key) {
        int space = key.indexOf(' ');
        String path = space >= 0 ? key.substring(space + 1) : key;
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    public long lastWarmedAt() {
        return lastWarmed;
    }

    public int lastWarmedCount() {
        return lastWarmedCount;
    }
}
