package com.adaptivecache;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.classifier.TrafficClassifier;
import com.adaptivecache.classifier.TrafficClassifier.ClassificationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-time statistical classifier: Hot / Bursty / One-Shot detection with a
 * fully deterministic injected clock.
 */
class TrafficClassifierTest {

    private TrafficClassifier classifier;
    private long now;

    @BeforeEach
    void setUp() {
        now = 1_000_000L;
        classifier = new TrafficClassifier(
                () -> now,
                60_000L,   // 60s observation window
                256,       // ring capacity
                2,         // freq <= 2 -> ONE_SHOT
                0.8,       // CV >= 0.8 -> BURSTY
                5.0);      // hot rate/min (informational)
    }

    private ClassificationReport hit(String key, long deltaMs) {
        now += deltaMs;
        return classifier.observe(key, now);
    }

    @Test
    void firstTwoRequestsAreOneShot() {
        ClassificationReport r1 = hit("/k1", 0);
        ClassificationReport r2 = hit("/k1", 100);
        assertEquals(TrafficClass.ONE_SHOT, r1.trafficClass());
        assertEquals(TrafficClass.ONE_SHOT, r2.trafficClass());
    }

    @Test
    void steadyFrequentTrafficIsHot() {
        hit("/k1", 0);
        hit("/k1", 100);
        hit("/k1", 100);
        hit("/k1", 100);
        ClassificationReport r = hit("/k1", 100);
        assertEquals(TrafficClass.HOT, r.trafficClass());
        assertTrue(r.coefficientOfVariation() < 0.8);
        assertEquals(5, r.lifetimeRequests());
    }

    @Test
    void spikyClusteredTrafficIsBursty() {
        hit("/k1", 0);
        hit("/k1", 50);
        hit("/k1", 50);
        hit("/k1", 9_000);   // long gap -> high variance
        hit("/k1", 50);
        ClassificationReport r = hit("/k1", 50);
        assertEquals(TrafficClass.BURSTY, r.trafficClass());
        assertTrue(r.coefficientOfVariation() >= 0.8);
    }

    @Test
    void oneShotPromotesToHotOnRepeat() {
        ClassificationReport r1 = hit("/k2", 0);
        assertEquals(TrafficClass.ONE_SHOT, r1.trafficClass());

        ClassificationReport r2 = hit("/k2", 100);
        ClassificationReport r3 = hit("/k2", 100);
        ClassificationReport r4 = hit("/k2", 100);
        assertEquals(TrafficClass.HOT, r4.trafficClass());
    }

    @Test
    void keysAreProfiledIndependently() {
        hit("/hot-a", 0);
        hit("/hot-a", 100);
        hit("/hot-a", 100);

        ClassificationReport other = hit("/other-b", 0);
        assertEquals(TrafficClass.ONE_SHOT, other.trafficClass());
    }

    @Test
    void frequencyPerMinuteUsesRollingWindow() {
        hit("/k1", 0);
        hit("/k1", 500);
        hit("/k1", 500);
        ClassificationReport r = hit("/k1", 500);
        assertEquals(4, r.requestsInWindow());
        assertEquals(4.0, r.frequencyPerMinute(), 0.001);
    }

    @Test
    void removeReleasesState() {
        hit("/k1", 0);
        assertEquals(1, classifier.trackedKeys());
        classifier.remove("/k1");
        assertEquals(0, classifier.trackedKeys());
        // Re-observing starts fresh -> ONE_SHOT again.
        ClassificationReport r = hit("/k1", 0);
        assertEquals(TrafficClass.ONE_SHOT, r.trafficClass());
    }
}
