package com.adaptivecache.predict;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight per-key demand forecaster: an exponentially weighted moving
 * average (EWMA) over per-second request rates. Zero dependencies, O(1)
 * updates, bounded memory — the "Traffic Predictor" block of the design.
 *
 * <p>Not a neural model by design (see PLAN.md Part 2): it captures the
 * trend signal CACS and the warming scheduler need without any ML runtime.</p>
 */
public final class EwmaPredictor {

    private static final class KeyModel {
        double rate;
        long lastTickSecond;
    }

    private final ConcurrentHashMap<String, KeyModel> models =
            new ConcurrentHashMap<>();
    private final double alpha;

    /** @param alpha smoothing factor in (0,1]; higher = more reactive */
    public EwmaPredictor(double alpha) {
        this.alpha = Math.max(0.01, Math.min(1.0, alpha));
    }

    public EwmaPredictor() {
        this(0.3);
    }

    /** Records one arrival for {@code key} at epoch millis. */
    public void observe(String key, long nowMillis) {
        long tick = nowMillis / 1000;
        models.compute(key, (k, m) -> {
            if (m == null) {
                m = new KeyModel();
                m.rate = 1.0;
                m.lastTickSecond = tick;
                return m;
            }
            synchronized (m) {
                long gap = Math.max(0, tick - m.lastTickSecond);
                double instant = gap == 0 ? m.rate + 1.0 : 1.0 / gap;
                m.rate = alpha * instant + (1 - alpha) * m.rate;
                m.lastTickSecond = tick;
            }
            return m;
        });
    }

    /**
     * Expected requests for {@code key} over the next
     * {@code horizonSeconds}, normalised to [0,1] against
     * {@code saturationRate}.
     */
    public double predict(String key, long horizonSeconds,
                          double saturationRate) {
        KeyModel m = models.get(key);
        if (m == null || saturationRate <= 0) {
            return 0.0;
        }
        double expected = m.rate * horizonSeconds;
        return Math.max(0.0, Math.min(1.0, expected / saturationRate));
    }

    public void remove(String key) {
        models.remove(key);
    }

    public int trackedKeys() {
        return models.size();
    }
}
