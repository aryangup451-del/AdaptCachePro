package com.adaptivecache;

import com.adaptivecache.predict.EwmaPredictor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EWMA demand predictor: reactivity, bounds and per-key isolation.
 */
class EwmaPredictorTest {

    @Test
    void unknownKeyPredictsZero() {
        EwmaPredictor p = new EwmaPredictor(0.3);
        assertEquals(0.0, p.predict("ghost", 60L, 100.0), 1e-9);
    }

    @Test
    void repeatedObservationsRaisePrediction() {
        EwmaPredictor p = new EwmaPredictor(0.5);
        long t = 10_000_000L;
        for (int i = 0; i < 20; i++) {
            p.observe("hot", t + i * 100);
        }
        double hot = p.predict("hot", 60L, 100.0);
        p.observe("cold", t);
        double cold = p.predict("cold", 60L, 100.0);
        assertTrue(hot > cold);
        assertTrue(hot >= 0.0 && hot <= 1.0);
    }

    @Test
    void predictionIsBounded() {
        EwmaPredictor p = new EwmaPredictor(0.9);
        long t = 5_000_000L;
        for (int i = 0; i < 500; i++) {
            p.observe("flood", t + i);
        }
        assertTrue(p.predict("flood", 3600L, 100.0) <= 1.0);
    }

    @Test
    void removeDropsState() {
        EwmaPredictor p = new EwmaPredictor();
        p.observe("k", 1000L);
        assertEquals(1, p.trackedKeys());
        p.remove("k");
        assertEquals(0, p.trackedKeys());
        assertEquals(0.0, p.predict("k", 60L, 100.0), 1e-9);
    }
}
