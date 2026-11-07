package com.adaptivecache;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.explain.DecisionExplainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explainable decisions: retention reasons and eviction narratives.
 */
class DecisionExplainerTest {

    @Test
    void retentionExplanationListsReasons() {
        DecisionExplainer explainer = new DecisionExplainer();
        CacheEntry e = new CacheEntry("GET /hot/1", 42L,
                new byte[512]);
        e.setTrafficClass(TrafficClass.HOT);
        e.setBackendLatencyMs(230);
        e.setPredictedScore(0.85);
        for (int i = 0; i < 10; i++) {
            e.bumpAccessCount();
        }
        long now = System.currentTimeMillis();
        e.applyPolicy(300_000L, 0L, 3_600_000L, TrafficClass.HOT, now);

        List<String> reasons = explainer.explainRetention(e, null);
        assertFalse(reasons.isEmpty());
        String joined = String.join("\n", reasons);
        assertTrue(joined.contains("230"));
        assertTrue(joined.contains("0.85"));
    }

    @Test
    void evictionExplanationNamesVictimAndScore() {
        DecisionExplainer explainer = new DecisionExplainer();
        CacheEntry victim = new CacheEntry("GET /scan/9", 7L, new byte[64]);
        victim.setTrafficClass(TrafficClass.ONE_SHOT);
        String text = explainer.explainEviction(victim, 0.11, 1024);
        assertTrue(text.contains("GET /scan/9"));
        assertTrue(text.contains("0.11"));
    }
}
