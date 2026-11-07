package com.adaptivecache.green;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Green-computing monitor (Item 9). Samples process CPU load via JMX and
 * converts it into energy (joules) and CO2 estimates using a linear TDP
 * model: power ≈ idle(10% TDP) + load × (TDP − idle).
 */
public final class EnergyMonitor {

    private final double tdpWatts;
    private final double co2GramsPerKwh;
    private final OperatingSystemMXBean osBean;

    private final AtomicLong sampleCount = new AtomicLong();
    private final AtomicReference<Double> lastCpuLoad = new AtomicReference<>(0.0);
    private volatile double totalJoules = 0.0;
    private volatile long lastSampleNanos = 0L;
    private final AtomicLong backendRequestsAvoided = new AtomicLong();

    public EnergyMonitor(double tdpWatts, double co2GramsPerKwh) {
        this.tdpWatts = tdpWatts;
        this.co2GramsPerKwh = co2GramsPerKwh;
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
    }

    public synchronized void sample() {
        double load = osBean.getSystemLoadAverage();
        int cpus = osBean.getAvailableProcessors();
        double normalized = load < 0 || cpus <= 0 ? 0.0
                : Math.min(1.0, load / cpus);
        long now = System.nanoTime();
        if (lastSampleNanos != 0) {
            double dtSeconds = (now - lastSampleNanos) / 1e9;
            double watts = 0.1 * tdpWatts + normalized * 0.9 * tdpWatts;
            totalJoules += watts * dtSeconds;
        }
        lastSampleNanos = now;
        lastCpuLoad.set(normalized);
        sampleCount.incrementAndGet();
    }

    public void recordAvoidedBackendRequest() {
        backendRequestsAvoided.incrementAndGet();
    }

    public double cpuUtilization() {
        Double v = lastCpuLoad.get();
        return v == null ? 0.0 : v;
    }

    public double totalJoules() {
        return totalJoules;
    }

    public double totalKwh() {
        return totalJoules / 3_600_000.0;
    }

    public double co2Grams() {
        return totalKwh() * co2GramsPerKwh;
    }

    public long samples() {
        return sampleCount.get();
    }

    public long avoidedBackendRequests() {
        return backendRequestsAvoided.get();
    }
}
