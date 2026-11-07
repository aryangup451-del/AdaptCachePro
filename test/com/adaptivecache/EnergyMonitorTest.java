package com.adaptivecache;

import com.adaptivecache.green.EnergyMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Energy monitor: monotonic energy accumulation and sane CO2 math.
 */
class EnergyMonitorTest {

    @Test
    void energyAccumulatesMonotonically() throws InterruptedException {
        EnergyMonitor monitor = new EnergyMonitor(65.0, 400.0);
        monitor.sample();
        Thread.sleep(50);
        monitor.sample();
        Thread.sleep(50);
        monitor.sample();
        assertTrue(monitor.totalJoules() >= 0.0);
        assertTrue(monitor.samples() >= 3);
        assertTrue(monitor.cpuUtilization() >= 0.0
                && monitor.cpuUtilization() <= 1.0);
    }

    @Test
    void co2DerivesFromEnergy() throws InterruptedException {
        EnergyMonitor monitor = new EnergyMonitor(65.0, 400.0);
        monitor.sample();
        Thread.sleep(60);
        monitor.sample();
        double expected = monitor.totalJoules() / 3_600_000.0 * 400.0;
        assertTrue(Math.abs(monitor.co2Grams() - expected) < 1e-9);
    }

    @Test
    void avoidedRequestsCounted() {
        EnergyMonitor monitor = new EnergyMonitor(65.0, 400.0);
        monitor.recordAvoidedBackendRequest();
        monitor.recordAvoidedBackendRequest();
        assertTrue(monitor.avoidedBackendRequests() == 2);
    }
}
