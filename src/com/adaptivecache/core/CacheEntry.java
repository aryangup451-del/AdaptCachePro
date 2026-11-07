package com.adaptivecache.core;

import com.adaptivecache.classifier.TrafficClass;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single cached HTTP response plus the adaptive metadata the policy engine
 * mutates over time (TTL, SWR window, class, priority, revalidation state).
 *
 * <p>All mutable state is either {@code volatile} or atomic so the proxy's
 * virtual threads can read metadata without taking the cache lock.</p>
 */
public final class CacheEntry {

    private final String key;
    private final long keyHash;

    private volatile byte[] body;
    private volatile String etag;
    private volatile String contentType;

    /** Epoch millis when this entry was (re)stored. */
    private volatile long storedAt;

    /** Epoch millis at which the entry becomes stale. */
    private volatile long expiresAt;

    /** RFC 5861 stale-while-revalidate window in millis (0 = none). */
    private volatile long swrWindowMs;

    /** RFC 5861 stale-if-error window in millis (0 = none). */
    private volatile long staleIfErrorMs;

    private volatile TrafficClass trafficClass;
    private volatile int priority;

    /** True while a background revalidation is in flight. */
    private volatile boolean revalidating;

    /** Epoch millis of the last successful revalidation. */
    private volatile long revalidatedAt;

    /** Total number of times this entry has been served. */
    private final AtomicLong accessCount = new AtomicLong();

    /** Origin round-trip latency (ms) measured when this entry was fetched. */
    private volatile long backendLatencyMs;

    /** EWMA demand forecast in [0,1] (updated by the proxy pipeline). */
    private volatile double predictedScore;

    public CacheEntry(String key, long keyHash, byte[] body) {
        this.key = key;
        this.keyHash = keyHash;
        this.body = body;
        this.trafficClass = TrafficClass.ONE_SHOT;
        this.priority = trafficClass.priority();
        this.storedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    public String getKey() {
        return key;
    }

    public long getKeyHash() {
        return keyHash;
    }

    public byte[] getBody() {
        return body;
    }

    public String getEtag() {
        return etag;
    }

    public String getContentType() {
        return contentType;
    }

    public long getStoredAt() {
        return storedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public long getSwrWindowMs() {
        return swrWindowMs;
    }

    public long getStaleIfErrorMs() {
        return staleIfErrorMs;
    }

    public TrafficClass getTrafficClass() {
        return trafficClass;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isRevalidating() {
        return revalidating;
    }

    public long getRevalidatedAt() {
        return revalidatedAt;
    }

    public long getAccessCount() {
        return accessCount.get();
    }

    public long getBackendLatencyMs() {
        return backendLatencyMs;
    }

    public double getPredictedScore() {
        return predictedScore;
    }

    public void setBackendLatencyMs(long backendLatencyMs) {
        this.backendLatencyMs = backendLatencyMs;
    }

    public void setPredictedScore(double predictedScore) {
        this.predictedScore = predictedScore;
    }

    public long bumpAccessCount() {
        return accessCount.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // Mutators (called by proxy pipeline + policy engine)
    // ------------------------------------------------------------------

    public void setBody(byte[] body) {
        this.body = body;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setStoredAt(long storedAt) {
        this.storedAt = storedAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public void setSwrWindowMs(long swrWindowMs) {
        this.swrWindowMs = swrWindowMs;
    }

    public void setStaleIfErrorMs(long staleIfErrorMs) {
        this.staleIfErrorMs = staleIfErrorMs;
    }

    public void setTrafficClass(TrafficClass trafficClass) {
        this.trafficClass = trafficClass;
        this.priority = trafficClass.priority();
    }

    public void setRevalidating(boolean revalidating) {
        this.revalidating = revalidating;
    }

    public void setRevalidatedAt(long revalidatedAt) {
        this.revalidatedAt = revalidatedAt;
    }

    /**
     * Applies a policy mutation: refreshes TTL / SWR / stale-if-error windows
     * anchored at the given instant.
     */
    public void applyPolicy(long ttlMs, long swrMs, long staleIfErrorMs,
                            TrafficClass trafficClass, long nowMillis) {
        this.expiresAt = nowMillis + ttlMs;
        this.swrWindowMs = swrMs;
        this.staleIfErrorMs = staleIfErrorMs;
        setTrafficClass(trafficClass);
    }
}
