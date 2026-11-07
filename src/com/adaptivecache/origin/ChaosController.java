package com.adaptivecache.origin;

/**
 * Chaos fault injection controller (deck slide 5, module 6).
 *
 * <p>Simulates realistic origin degradation so AdaptCache Pro's resilience
 * (stale-if-error, SWR) can be validated:</p>
 * <ul>
 *   <li>{@code latencyMs} — artificial per-request delay</li>
 *   <li>{@code failRate} — probability (0..1) of a 500 response</li>
 *   <li>{@code dropRate} — probability (0..1) of closing without a response</li>
 * </ul>
 *
 * <p>State is volatile + synchronized — mutated from admin HTTP handlers,
 * read from every request handler.</p>
 */
public final class ChaosController {

    public enum Mode { CLEAN, DEGRADED, DOWN }

    private volatile long latencyMs = 0;
    private volatile double failRate = 0.0;
    private volatile double dropRate = 0.0;
    private volatile Mode mode = Mode.CLEAN;

    private volatile long injectedFailures = 0;
    private volatile long injectedDrops = 0;
    private volatile long injectedLatencyEvents = 0;

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = Math.max(0, latencyMs);
    }

    public void setFailRate(double failRate) {
        this.failRate = clamp01(failRate);
    }

    public void setDropRate(double dropRate) {
        this.dropRate = clamp01(dropRate);
    }

    public void applyMode(Mode mode) {
        this.mode = mode;
        switch (mode) {
            case CLEAN -> {
                this.latencyMs = 0;
                this.failRate = 0.0;
                this.dropRate = 0.0;
            }
            case DEGRADED -> {
                this.latencyMs = 150;
                this.failRate = 0.25;
                this.dropRate = 0.05;
            }
            case DOWN -> {
                this.latencyMs = 0;
                this.failRate = 1.0;
                this.dropRate = 0.0;
            }
            default -> { /* no-op */ }
        }
    }

    /**
     * Applies configured chaos to the current request.
     *
     * @return the injected latency that the caller must sleep for, or
     *         {@code -1} if the request should be dropped, or
     *         {@code -2} if a 500 should be returned
     */
    public synchronized int inject() {
        long latency = latencyMs;
        double fail = failRate;
        double drop = dropRate;

        if (drop > 0.0 && Math.random() < drop) {
            injectedDrops++;
            return -1;
        }
        if (fail > 0.0 && Math.random() < fail) {
            injectedFailures++;
            return -2;
        }
        if (latency > 0) {
            injectedLatencyEvents++;
            return (int) latency;
        }
        return 0;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public double getFailRate() {
        return failRate;
    }

    public double getDropRate() {
        return dropRate;
    }

    public Mode getMode() {
        return mode;
    }

    public long getInjectedFailures() {
        return injectedFailures;
    }

    public long getInjectedDrops() {
        return injectedDrops;
    }

    public long getInjectedLatencyEvents() {
        return injectedLatencyEvents;
    }

    /** JSON-ish status payload for the admin endpoint. */
    public String statusJson() {
        return "{\"mode\":\"" + mode
                + "\",\"latencyMs\":" + latencyMs
                + ",\"failRate\":" + failRate
                + ",\"dropRate\":" + dropRate
                + ",\"injectedFailures\":" + injectedFailures
                + ",\"injectedDrops\":" + injectedDrops
                + ",\"injectedLatencyEvents\":" + injectedLatencyEvents
                + "}";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
