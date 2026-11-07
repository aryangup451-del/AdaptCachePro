package com.adaptivecache.explain;

import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.policy.CacsPolicy;
import com.adaptivecache.stats.RequestStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Explainable cache decisions (Item 7). Renders per-entry human-readable
 * reasons answering "why is this object retained / evicted?".
 */
public final class DecisionExplainer {

    private final CacsPolicy scorer;

    public DecisionExplainer() {
        this(new CacsPolicy());
    }

    public DecisionExplainer(CacsPolicy scorer) {
        this.scorer = scorer;
    }

    public List<String> explainRetention(CacheEntry entry, RequestStats stats) {
        List<String> reasons = new ArrayList<>();
        long now = System.currentTimeMillis();

        double score = scorer.score(entry, now, 3_600_000L, 0);
        reasons.add(String.format(Locale.ROOT,
                "CACS value score %.2f/1.00 (%s class, priority %d)",
                score, entry.getTrafficClass(), entry.getPriority()));

        if (stats != null && stats.getRequestCount() > 0) {
            reasons.add(String.format(Locale.ROOT,
                    "%d requests, %.1f%% hit rate, %.1f ms avg latency",
                    stats.getRequestCount(), stats.getHitRate() * 100,
                    stats.getAverageLatencyMs()));
        }
        reasons.add(String.format(Locale.ROOT,
                "%d lifetime serves of %d-byte object",
                entry.getAccessCount(),
                entry.getBody() == null ? 0 : entry.getBody().length));
        if (entry.getBackendLatencyMs() > 0) {
            reasons.add(String.format(Locale.ROOT,
                    "avoids ~%d ms backend round-trip per hit",
                    entry.getBackendLatencyMs()));
        }
        if (entry.getPredictedScore() > 0.5) {
            reasons.add(String.format(Locale.ROOT,
                    "high predicted demand (%.2f)",
                    entry.getPredictedScore()));
        }
        long ttlLeft = Math.max(0, (entry.getExpiresAt() - now) / 1000);
        reasons.add("fresh for " + ttlLeft + "s more"
                + (entry.getSwrWindowMs() > 0
                ? ", then SWR-eligible for "
                        + (entry.getSwrWindowMs() / 1000) + "s" : ""));
        return reasons;
    }

    public String explainEviction(CacheEntry victim, double victimScore,
                                  int cacheSize) {
        return String.format(Locale.ROOT,
                "Evicted '%s': lowest CACS score (%.2f) among scanned "
                        + "candidates at capacity %d; %s, %d serves, %d ms "
                        + "backend latency saved.",
                victim.getKey(), victimScore, cacheSize,
                victim.getTrafficClass(), victim.getAccessCount(),
                victim.getBackendLatencyMs());
    }
}
