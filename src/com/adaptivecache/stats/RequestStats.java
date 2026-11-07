package com.adaptivecache.stats;

import com.adaptivecache.classifier.TrafficClass;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe per-key request statistics: hit/miss/stale counts, latency and
 * inter-arrival tracking, per-class counters.
 */
public final class RequestStats {

    private final String key;

    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong staleCount = new AtomicLong();
    private final AtomicLong revalidatedCount = new AtomicLong();
    private final AtomicLong peerHitCount = new AtomicLong();

    private final AtomicLong totalLatencyMs = new AtomicLong();
    private volatile long responseSize = 0;
    private volatile long lastAccessTime = 0;
    private final AtomicLong totalInterArrivalMs = new AtomicLong();

    private final Map<TrafficClass, AtomicLong> byClass =
            new EnumMap<>(TrafficClass.class);

    public RequestStats(String key) {
        this.key = key;
        for (TrafficClass c : TrafficClass.values()) {
            byClass.put(c, new AtomicLong());
        }
    }

    public enum Outcome { HIT, MISS, STALE, REVALIDATED, PEER_HIT }

    public void record(Outcome outcome, TrafficClass clazz,
                       long latencyMs, long size) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (lastAccessTime != 0) {
                totalInterArrivalMs.addAndGet(Math.max(0, now - lastAccessTime));
            }
            lastAccessTime = now;
        }
        requestCount.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        responseSize = size;
        byClass.get(clazz).incrementAndGet();
        switch (outcome) {
            case HIT -> hitCount.incrementAndGet();
            case MISS -> missCount.incrementAndGet();
            case STALE -> staleCount.incrementAndGet();
            case REVALIDATED -> revalidatedCount.incrementAndGet();
            case PEER_HIT -> {
                hitCount.incrementAndGet();
                peerHitCount.incrementAndGet();
            }
        }
    }

    public String getKey() {
        return key;
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    public long getHitCount() {
        return hitCount.get();
    }

    public long getMissCount() {
        return missCount.get();
    }

    public long getStaleCount() {
        return staleCount.get();
    }

    public long getRevalidatedCount() {
        return revalidatedCount.get();
    }

    public long getPeerHitCount() {
        return peerHitCount.get();
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs.get();
    }

    public long getResponseSize() {
        return responseSize;
    }

    public long getTotalInterArrivalMs() {
        return totalInterArrivalMs.get();
    }

    public long countByClass(TrafficClass clazz) {
        return byClass.get(clazz).get();
    }

    /** Hit rate in [0,1] (hits + stale serves count as cache-offloaded). */
    public double getHitRate() {
        long total = requestCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) (hitCount.get() + staleCount.get()
                + revalidatedCount.get()) / total;
    }

    public double getAverageLatencyMs() {
        long total = requestCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) totalLatencyMs.get() / total;
    }

    public double getAverageInterArrivalMs() {
        long total = requestCount.get();
        if (total <= 1) {
            return 0.0;
        }
        return (double) totalInterArrivalMs.get() / (total - 1);
    }
}
