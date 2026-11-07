package com.adaptivecache;

import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.stats.RequestStatsRegistry;
import com.adaptivecache.stats.RequestStats.Outcome;
import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.tuning.SelfTuningController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-tuning controller: TTL scale rises under miss pressure, capacity
 * grows, and scales relax back toward 1.0 at target.
 */
class SelfTuningControllerTest {

    private static LRUCache cache(int capacity) {
        return new LRUCache(capacity);
    }

    @Test
    void ttlScaleRisesWhenHitRatioMissesTarget() {
        LRUCache c = cache(100);
        RequestStatsRegistry stats = new RequestStatsRegistry();
        for (int i = 0; i < 100; i++) {
            stats.record("k" + (i % 90), Outcome.MISS,
                    TrafficClass.ONE_SHOT, 5, 100, false);
        }
        SelfTuningController tuner = new SelfTuningController(c, stats,
                0.95, 60);
        tuner.adjust();
        assertTrue(tuner.ttlScale() > 1.0);
        assertTrue(tuner.adjustments() >= 1);
    }

    @Test
    void capacityGrowsUnderPressure() {
        LRUCache c = cache(100);
        c.setActiveCapacity(40);
        RequestStatsRegistry stats = new RequestStatsRegistry();
        for (int i = 0; i < 100; i++) {
            stats.record("k" + i, Outcome.MISS,
                    TrafficClass.ONE_SHOT, 5, 100, false);
        }
        SelfTuningController tuner = new SelfTuningController(c, stats,
                0.95, 60);
        tuner.adjust();
        assertTrue(c.activeCapacity() > 40);
    }

    @Test
    void scalesRelaxTowardOneAtTarget() {
        LRUCache c = cache(100);
        RequestStatsRegistry stats = new RequestStatsRegistry();
        for (int i = 0; i < 60; i++) {
            stats.record("hot", Outcome.HIT, TrafficClass.HOT, 1, 100, true);
        }
        for (int i = 0; i < 9; i++) {
            stats.record("cold-" + i, Outcome.MISS,
                    TrafficClass.ONE_SHOT, 5, 100, false);
        }
        SelfTuningController tuner = new SelfTuningController(c, stats,
                0.85, 60);
        tuner.adjust();
        assertEquals(1.0, tuner.ttlScale(), 0.2);
    }

    @Test
    void insufficientTrafficSkipsAdjustment() {
        LRUCache c = cache(100);
        RequestStatsRegistry stats = new RequestStatsRegistry();
        stats.record("k", Outcome.MISS, TrafficClass.ONE_SHOT, 5, 100, false);
        SelfTuningController tuner = new SelfTuningController(c, stats,
                0.95, 60);
        tuner.adjust();
        assertEquals(0, tuner.adjustments());
        assertEquals(1.0, tuner.ttlScale(), 1e-9);
    }

    @Test
    void putHelperExists() {
        CacheEntry e = new CacheEntry("k", 1L, new byte[10]);
        assertEquals(10, e.getBody().length);
    }
}
