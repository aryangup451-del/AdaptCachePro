package com.adaptivecache;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.classifier.TrafficClassifier.ClassificationReport;
import com.adaptivecache.policy.AdaptivePolicyEngine;
import com.adaptivecache.policy.AdaptivePolicyEngine.CachePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adaptive policy mapping (deck slide 4): TTL 300/60/10, SWR for bursty
 * keys only, retention priorities.
 */
class AdaptivePolicyEngineTest {

    private final AdaptivePolicyEngine engine =
            new AdaptivePolicyEngine(300, 60, 10, 60, 3600);

    private static ClassificationReport report(TrafficClass clazz) {
        return new ClassificationReport("k", clazz, 10, 10, 10.0,
                100.0, 0.0, 0.0);
    }

    @Test
    void hotKeysGetLongTtlAndBackgroundRefresh() {
        CachePolicy p = engine.policyFor(report(TrafficClass.HOT));
        assertEquals(300, p.ttlSeconds());
        assertEquals(0, p.swrSeconds());
        assertTrue(p.backgroundRefresh());
        assertEquals(3, p.priority());
    }

    @Test
    void burstyKeysGetMediumTtlAndSwr() {
        CachePolicy p = engine.policyFor(report(TrafficClass.BURSTY));
        assertEquals(60, p.ttlSeconds());
        assertEquals(60, p.swrSeconds());
        assertFalse(p.backgroundRefresh());
        assertEquals(2, p.priority());
    }

    @Test
    void oneShotKeysGetShortTtlAndLowestPriority() {
        CachePolicy p = engine.policyFor(report(TrafficClass.ONE_SHOT));
        assertEquals(10, p.ttlSeconds());
        assertEquals(0, p.swrSeconds());
        assertEquals(1, p.priority());
    }

    @Test
    void millisConversionsAreCorrect() {
        CachePolicy hot = engine.policyFor(report(TrafficClass.HOT));
        assertEquals(300_000L, hot.ttlMillis());
        CachePolicy bursty = engine.policyFor(report(TrafficClass.BURSTY));
        assertEquals(60_000L, bursty.swrMillis());
    }

    @Test
    void staleIfErrorWindowIsGlobal() {
        assertEquals(3600, engine.staleIfErrorSeconds());
    }
}
