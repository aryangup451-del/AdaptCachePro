package com.adaptivecache.policy;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.classifier.TrafficClassifier.ClassificationReport;
import com.adaptivecache.config.AdaptCacheConfig;

/**
 * Maps classification reports onto concrete cache policies: differentiated
 * TTLs, RFC 5861 SWR windows, retention priority and background refresh flags.
 *
 * <p>Mapping (deck slide 4):</p>
 * <ul>
 *   <li>HOT       → TTL 300s, background async revalidation, priority 3</li>
 *   <li>BURSTY    → TTL 60s, stale-while-revalidate 60s, priority 2</li>
 *   <li>ONE_SHOT  → TTL 10s, no SWR, priority 1 (evicted first)</li>
 * </ul>
 */
public final class AdaptivePolicyEngine {

    /** A concrete, immutable policy decision. */
    public record CachePolicy(
            TrafficClass trafficClass,
            long ttlSeconds,
            long swrSeconds,
            boolean backgroundRefresh,
            int priority) {

        public long ttlMillis() {
            return ttlSeconds * 1_000L;
        }

        public long swrMillis() {
            return swrSeconds * 1_000L;
        }
    }

    private final long hotTtlSeconds;
    private final long burstyTtlSeconds;
    private final long oneShotTtlSeconds;
    private final long burstySwrSeconds;
    private final long staleIfErrorSeconds;

    public AdaptivePolicyEngine() {
        this(AdaptCacheConfig.HOT_TTL_SECONDS,
                AdaptCacheConfig.BURSTY_TTL_SECONDS,
                AdaptCacheConfig.ONE_SHOT_TTL_SECONDS,
                AdaptCacheConfig.BURSTY_SWR_SECONDS,
                AdaptCacheConfig.STALE_IF_ERROR_SECONDS);
    }

    /** Injectable constants (tests use tiny TTLs). */
    public AdaptivePolicyEngine(long hotTtlSeconds, long burstyTtlSeconds,
                                long oneShotTtlSeconds, long burstySwrSeconds,
                                long staleIfErrorSeconds) {
        this.hotTtlSeconds = hotTtlSeconds;
        this.burstyTtlSeconds = burstyTtlSeconds;
        this.oneShotTtlSeconds = oneShotTtlSeconds;
        this.burstySwrSeconds = burstySwrSeconds;
        this.staleIfErrorSeconds = staleIfErrorSeconds;
    }

    /**
     * Decides the policy for a freshly classified key.
     */
    public CachePolicy policyFor(ClassificationReport report) {
        return policyFor(report.trafficClass());
    }

    /**
     * Decides the policy for a traffic class.
     */
    public CachePolicy policyFor(TrafficClass clazz) {
        return switch (clazz) {
            case HOT -> new CachePolicy(TrafficClass.HOT, hotTtlSeconds, 0,
                    true, TrafficClass.HOT.priority());
            case BURSTY -> new CachePolicy(TrafficClass.BURSTY, burstyTtlSeconds,
                    burstySwrSeconds, false, TrafficClass.BURSTY.priority());
            case ONE_SHOT -> new CachePolicy(TrafficClass.ONE_SHOT,
                    oneShotTtlSeconds, 0, false, TrafficClass.ONE_SHOT.priority());
        };
    }

    /** RFC 5861 stale-if-error window applied to every entry. */
    public long staleIfErrorSeconds() {
        return staleIfErrorSeconds;
    }
}
