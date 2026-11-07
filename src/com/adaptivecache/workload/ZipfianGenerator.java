package com.adaptivecache.workload;

import java.util.Random;
import java.util.TreeMap;

/**
 * Zipfian (power-law) distribution generator — models the popularity skew of
 * real-world API traffic: a small set of "hot" keys receive the majority of
 * requests (deck slide 5, module 1).
 *
 * <p>Implementation: CDF sampling with a hard cap on the number of distinct
 * keys, precomputed once for O(log n) sampling.</p>
 */
public final class ZipfianGenerator {

    private final int itemCount;
    private final double skew;
    private final TreeMap<Double, Integer> cdf = new TreeMap<>();
    private final Random random;

    public ZipfianGenerator(int itemCount, double skew, long seed) {
        this.itemCount = Math.max(1, itemCount);
        this.skew = skew;
        this.random = new Random(seed);
        buildCdf();
    }

    private void buildCdf() {
        double sum = 0.0;
        double[] weights = new double[itemCount];
        for (int i = 0; i < itemCount; i++) {
            weights[i] = 1.0 / Math.pow(i + 1, skew);
            sum += weights[i];
        }
        double cumulative = 0.0;
        for (int i = 0; i < itemCount; i++) {
            cumulative += weights[i] / sum;
            cdf.put(cumulative, i);
        }
    }

    /** Returns a rank in [0, itemCount) with Zipfian probability. */
    public int nextRank() {
        double u = random.nextDouble();
        return cdf.ceilingEntry(u).getValue();
    }

    /**
     * Returns the key path for a rank. Ranks &lt; hotShare map onto the /hot/*
     * namespace so the workload exercises all three traffic profiles.
     *
     * @param hotShare  fraction (0..1) of top ranks mapped to /hot/
     * @param burstyShare  fraction of next ranks mapped to /bursty/
     */
    public String pathForRank(int rank, double hotShare, double burstyShare) {
        int hotCut = Math.max(1, (int) (itemCount * hotShare));
        int burstyCut = hotCut
                + Math.max(0, (int) (itemCount * burstyShare));
        if (rank < hotCut) {
            return "/hot/item-" + rank;
        }
        if (rank < burstyCut) {
            return "/bursty/item-" + rank;
        }
        return "/oneshot/item-" + rank;
    }

    public int itemCount() {
        return itemCount;
    }

    public double skew() {
        return skew;
    }
}
