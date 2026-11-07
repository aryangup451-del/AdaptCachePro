package com.adaptivecache.stats;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.config.AdaptCacheConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global registry aggregating per-key {@link RequestStats} plus proxy-wide
 * counters. Backs the /stats and /metrics endpoints.
 *
 * <p>Bounded: at most {@link AdaptCacheConfig#MAX_TRACKED_KEYS} keys are
 * tracked; overflow entries are dropped (oldest-first via insertion order).</p>
 */
public final class RequestStatsRegistry {

    private final Map<String, RequestStats> perKey =
            new ConcurrentHashMap<>();

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalHits = new AtomicLong();
    private final AtomicLong totalMisses = new AtomicLong();
    private final AtomicLong totalStaleServes = new AtomicLong();
    private final AtomicLong totalRevalidations = new AtomicLong();
    private final AtomicLong totalPeerHits = new AtomicLong();
    private final AtomicLong totalOriginErrors = new AtomicLong();
    private final AtomicLong totalBytesServedFromCache = new AtomicLong();
    private final AtomicLong totalBytesFetchedFromOrigin = new AtomicLong();
    private final AtomicLong totalOriginRoundTrips = new AtomicLong();

    private final Map<TrafficClass, AtomicLong> requestsByClass =
            new EnumMap<>(TrafficClass.class);

    private final int maxTrackedKeys;

    public RequestStatsRegistry() {
        this(AdaptCacheConfig.MAX_TRACKED_KEYS);
    }

    public RequestStatsRegistry(int maxTrackedKeys) {
        this.maxTrackedKeys = maxTrackedKeys;
        for (TrafficClass c : TrafficClass.values()) {
            requestsByClass.put(c, new AtomicLong());
        }
    }

    public void record(String key, RequestStats.Outcome outcome,
                       TrafficClass clazz, long latencyMs, long size,
                       boolean servedFromCache) {
        RequestStats stats = perKey.get(key);
        if (stats == null) {
            if (perKey.size() >= maxTrackedKeys) {
                stats = new RequestStats(key); // untracked overflow bucket
            } else {
                RequestStats created = new RequestStats(key);
                RequestStats existing = perKey.putIfAbsent(key, created);
                stats = existing != null ? existing : created;
            }
        }
        stats.record(outcome, clazz, latencyMs, size);

        totalRequests.incrementAndGet();
        requestsByClass.get(clazz).incrementAndGet();
        switch (outcome) {
            case HIT -> totalHits.incrementAndGet();
            case MISS -> totalMisses.incrementAndGet();
            case STALE -> totalStaleServes.incrementAndGet();
            case REVALIDATED -> totalRevalidations.incrementAndGet();
            case PEER_HIT -> {
                totalHits.incrementAndGet();
                totalPeerHits.incrementAndGet();
            }
        }
        if (servedFromCache) {
            totalBytesServedFromCache.addAndGet(size);
        } else {
            totalBytesFetchedFromOrigin.addAndGet(size);
            totalOriginRoundTrips.incrementAndGet();
        }
    }

    public void recordOriginError() {
        totalOriginErrors.incrementAndGet();
    }

    /** Snapshot of the registry for rendering. */
    public List<RequestStats> snapshot() {
        List<RequestStats> list = new ArrayList<>(perKey.values());
        list.sort(Comparator.comparingLong(RequestStats::getRequestCount)
                .reversed());
        return list;
    }

    public int trackedKeys() {
        return perKey.size();
    }

    // ------------------------------------------------------------------
    // Aggregate getters
    // ------------------------------------------------------------------

    public long totalRequests() {
        return totalRequests.get();
    }

    public long totalHits() {
        return totalHits.get();
    }

    public long totalMisses() {
        return totalMisses.get();
    }

    public long totalStaleServes() {
        return totalStaleServes.get();
    }

    public long totalRevalidations() {
        return totalRevalidations.get();
    }

    public long totalPeerHits() {
        return totalPeerHits.get();
    }

    public void recordPeerHit() {
        totalPeerHits.incrementAndGet();
    }

    public long totalOriginErrors() {
        return totalOriginErrors.get();
    }

    public long totalBytesServedFromCache() {
        return totalBytesServedFromCache.get();
    }

    public long totalBytesFetchedFromOrigin() {
        return totalBytesFetchedFromOrigin.get();
    }

    public long totalOriginRoundTrips() {
        return totalOriginRoundTrips.get();
    }

    public long requestsByClass(TrafficClass clazz) {
        return requestsByClass.get(clazz).get();
    }

    /** Overall cache offload ratio (hits + stale + revalidated) / total. */
    public double overallHitRatio() {
        long total = totalRequests();
        if (total == 0) {
            return 0.0;
        }
        return (double) (totalHits() + totalStaleServes()
                + totalRevalidations()) / total;
    }

    /** Fraction of requests that never reached the origin. */
    public double originOffloadRatio() {
        long total = totalRequests();
        if (total == 0) {
            return 0.0;
        }
        return 1.0 - (double) totalOriginRoundTrips() / total;
    }
}
