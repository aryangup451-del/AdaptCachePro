package com.adaptivecache.classifier;

import com.adaptivecache.config.AdaptCacheConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Real-time statistical profiler: maintains a bounded {@link RingBuffer} per
 * cache key and computes frequency (λ) and inter-arrival variance (σ²) over a
 * rolling observation window, then maps the traffic into {@link TrafficClass}.
 *
 * <p>Classification rules (deck slide 4):</p>
 * <ul>
 *   <li>lifetime frequency ≤ {@link AdaptCacheConfig#ONE_SHOT_MAX_FREQUENCY}
 *       → ONE_SHOT</li>
 *   <li>coefficient of variation (σ/μ of inter-arrival deltas) ≥ threshold
 *       → BURSTY (spiky / clustered)</li>
 *   <li>otherwise → HOT (steady &amp; frequent)</li>
 * </ul>
 */
public final class TrafficClassifier {

    /** Everything the policy engine needs about one observation. */
    public record ClassificationReport(
            String key,
            TrafficClass trafficClass,
            long lifetimeRequests,
            long requestsInWindow,
            double frequencyPerMinute,
            double interArrivalMeanMs,
            double interArrivalVarianceMs,
            double coefficientOfVariation) {

        public boolean isHot() {
            return trafficClass == TrafficClass.HOT;
        }

        public boolean isBursty() {
            return trafficClass == TrafficClass.BURSTY;
        }

        public boolean isOneShot() {
            return trafficClass == TrafficClass.ONE_SHOT;
        }
    }

    private static final class KeyState {
        final RingBuffer ring;
        long lifetimeRequests;

        KeyState(int capacity) {
            this.ring = new RingBuffer(capacity);
            this.lifetimeRequests = 0;
        }
    }

    private final ConcurrentHashMap<String, KeyState> states =
            new ConcurrentHashMap<>();

    private final LongSupplier nowMillis;
    private final long windowMs;
    private final int ringCapacity;
    private final int oneShotMaxFrequency;
    private final double burstyCvThreshold;
    private final double hotMinRatePerMin;

    public TrafficClassifier() {
        this(System::currentTimeMillis,
                AdaptCacheConfig.OBSERVATION_WINDOW_MS,
                AdaptCacheConfig.RING_BUFFER_CAPACITY,
                AdaptCacheConfig.ONE_SHOT_MAX_FREQUENCY,
                AdaptCacheConfig.BURSTY_CV_THRESHOLD,
                AdaptCacheConfig.HOT_MIN_RATE_PER_MIN);
    }

    /** Injectable clock + thresholds (used by tests with a fake clock). */
    public TrafficClassifier(LongSupplier nowMillis, long windowMs,
                             int ringCapacity, int oneShotMaxFrequency,
                             double burstyCvThreshold, double hotMinRatePerMin) {
        this.nowMillis = nowMillis;
        this.windowMs = windowMs;
        this.ringCapacity = ringCapacity;
        this.oneShotMaxFrequency = oneShotMaxFrequency;
        this.burstyCvThreshold = burstyCvThreshold;
        this.hotMinRatePerMin = hotMinRatePerMin;
    }

    /**
     * Records an access for {@code key} at the current instant and returns
     * the fresh classification report.
     */
    public ClassificationReport observe(String key) {
        return observe(key, nowMillis.getAsLong());
    }

    /**
     * Records an access for {@code key} at {@code timestampMillis} and
     * returns the fresh classification report.
     */
    public ClassificationReport observe(String key, long timestampMillis) {
        KeyState state = states.computeIfAbsent(
                key, k -> new KeyState(ringCapacity));

        synchronized (state) {
            state.lifetimeRequests++;
            state.ring.record(timestampMillis);
            return classify(key, state, timestampMillis);
        }
    }

    /** Classification logic — pure function of state + instant. */
    private ClassificationReport classify(String key, KeyState state, long now) {
        long inWindow = state.ring.countWithin(now, windowMs);
        double[] deltas = state.ring.interArrivalDeltas(now, windowMs);

        double mean = 0.0;
        double variance = 0.0;
        if (deltas.length > 0) {
            double sum = 0.0;
            for (double d : deltas) {
                sum += d;
            }
            mean = sum / deltas.length;
            if (deltas.length > 1) {
                double sqSum = 0.0;
                for (double d : deltas) {
                    double diff = d - mean;
                    sqSum += diff * diff;
                }
                variance = sqSum / (deltas.length - 1);
            }
        }
        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean : 0.0;

        double freqPerMin = inWindow > 0
                ? inWindow / (windowMs / 60_000.0)
                : 0.0;

        TrafficClass clazz;
        if (state.lifetimeRequests <= oneShotMaxFrequency) {
            clazz = TrafficClass.ONE_SHOT;
        } else if (cv >= burstyCvThreshold) {
            clazz = TrafficClass.BURSTY;
        } else {
            clazz = TrafficClass.HOT;
        }

        return new ClassificationReport(
                key, clazz, state.lifetimeRequests, inWindow,
                freqPerMin, mean, variance, cv);
    }

    /**
     * Removes per-key state (called by the cache eviction listener to keep
     * memory bounded).
     */
    public void remove(String key) {
        states.remove(key);
    }

    /** Number of keys currently being profiled. */
    public int trackedKeys() {
        return states.size();
    }

    public long windowMs() {
        return windowMs;
    }
}
