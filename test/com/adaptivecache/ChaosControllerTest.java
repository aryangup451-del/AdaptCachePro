package com.adaptivecache;

import com.adaptivecache.origin.ChaosController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos fault injection: mode presets and per-request injection semantics.
 */
class ChaosControllerTest {

    @Test
    void cleanModeNeverInjectsFaults() {
        ChaosController chaos = new ChaosController();
        chaos.applyMode(ChaosController.Mode.CLEAN);
        for (int i = 0; i < 500; i++) {
            assertEquals(0, chaos.inject());
        }
    }

    @Test
    void downModeAlwaysFails() {
        ChaosController chaos = new ChaosController();
        chaos.applyMode(ChaosController.Mode.DOWN);
        for (int i = 0; i < 100; i++) {
            assertEquals(-2, chaos.inject());
        }
    }

    @Test
    void degradedModeAppliesConfiguredRates() {
        ChaosController chaos = new ChaosController();
        chaos.setLatencyMs(25);
        chaos.setFailRate(0.0);
        chaos.setDropRate(0.0);
        for (int i = 0; i < 50; i++) {
            assertEquals(25, chaos.inject());
        }
        assertEquals(50, chaos.getInjectedLatencyEvents());
    }

    @Test
    void fullFailRateDropsNothingButFailsEverything() {
        ChaosController chaos = new ChaosController();
        chaos.setFailRate(1.0);
        chaos.setDropRate(0.0);
        for (int i = 0; i < 50; i++) {
            assertEquals(-2, chaos.inject());
        }
    }

    @Test
    void ratesAreClamped() {
        ChaosController chaos = new ChaosController();
        chaos.setFailRate(7.5);
        chaos.setDropRate(-3.0);
        assertEquals(1.0, chaos.getFailRate(), 1e-9);
        assertEquals(0.0, chaos.getDropRate(), 1e-9);
    }

    @Test
    void statusJsonReportsState() {
        ChaosController chaos = new ChaosController();
        chaos.applyMode(ChaosController.Mode.DEGRADED);
        String json = chaos.statusJson();
        assertTrue(json.contains("\"mode\":\"DEGRADED\""));
        assertTrue(json.contains("\"latencyMs\":150"));
    }
}
