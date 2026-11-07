package com.adaptivecache.tuning;

import com.adaptivecache.core.LRUCache;
import com.adaptivecache.stats.RequestStatsRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-tuning controller (Item 5). A background control loop steers the
 * cache toward a hit-ratio target by adjusting the TTL scale factor and
 * the logical active capacity — no manual tuning required.
 */
public final class SelfTuningController {

    private final LRUCache cache;
    private final RequestStatsRegistry stats;
    private final double targetHitRatio;
    private final long intervalSeconds;

    private volatile double ttlScale = 1.0;
    private volatile double swrScale = 1.0;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile long adjustments = 0;

    public SelfTuningController(LRUCache cache, RequestStatsRegistry stats,
                                double targetHitRatio, long intervalSeconds) {
        this.cache = cache;
        this.stats = stats;
        this.targetHitRatio = targetHitRatio;
        this.intervalSeconds = Math.max(1, intervalSeconds);
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            worker = Thread.ofVirtual().name("self-tuner").start(this::loop);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    public double ttlScale() {
        return ttlScale;
    }

    public double swrScale() {
        return swrScale;
    }

    public long adjustments() {
        return adjustments;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        while (running.get()) {
            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running.get()) {
                adjust();
            }
        }
    }

    public void adjust() {
        double hr = stats.overallHitRatio();
        if (stats.totalRequests() < 50) {
            return;
        }
        double error = targetHitRatio - hr;
        if (error > 0.05) {
            ttlScale = Math.min(2.0, ttlScale * 1.15);
            swrScale = Math.min(2.0, swrScale * 1.10);
            int grown = (int) (cache.activeCapacity() * 1.1) + 1;
            cache.setActiveCapacity(Math.min(grown, cache.capacity()));
            adjustments++;
        } else if (error < -0.10) {
            ttlScale = Math.max(0.5, ttlScale * 0.95);
            swrScale = Math.max(0.5, swrScale * 0.95);
            adjustments++;
        } else {
            ttlScale += (1.0 - ttlScale) * 0.1;
            swrScale += (1.0 - swrScale) * 0.1;
        }
    }
}
