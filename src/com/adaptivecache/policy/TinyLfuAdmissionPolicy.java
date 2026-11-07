package com.adaptivecache.policy;

import com.adaptivecache.core.CacheEntry;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * TinyLFU admission filter (Einziger et al.): a compact Count-Min Sketch
 * tracks approximate access frequency per key, and a candidate is admitted
 * only when its estimated frequency justifies evicting something.
 *
 * <p>This implementation uses the sketch estimate at admission time versus
 * the minimum access count inside the cache's eviction scan window —
 * one-shot scans are rejected before they can thrash hot entries.</p>
 */
public final class TinyLfuAdmissionPolicy implements AdmissionPolicy {

    private static final int DEPTH = 4;

    private final AtomicIntegerArray[] sketch;
    private final int width;
    private final int[] seeds;
    private final java.util.function.IntSupplier cacheMinCount;
    private final java.util.concurrent.atomic.AtomicLong totalIncrements =
            new java.util.concurrent.atomic.AtomicLong();
    private final long resetPeriod;

    /**
     * @param width         sketch width (counters per row)
     * @param cacheMinCount supplier of the current minimum access count
     *                      among cached entries (eviction candidates)
     */
    public TinyLfuAdmissionPolicy(int width,
                                  java.util.function.IntSupplier cacheMinCount) {
        this.width = Math.max(16, width);
        this.sketch = new AtomicIntegerArray[DEPTH];
        for (int i = 0; i < DEPTH; i++) {
            sketch[i] = new AtomicIntegerArray(this.width);
        }
        this.seeds = new int[]{0x9e3779b9, 0x85ebca6b, 0xc2b2ae35, 0x27d4eb2f};
        this.cacheMinCount = cacheMinCount;
        this.resetPeriod = this.width;
    }

    /** Records an access (called on every request, hit or miss). */
    public void recordAccess(String key) {
        long hash = mix64(key.hashCode());
        for (int i = 0; i < DEPTH; i++) {
            int idx = (int) ((hash ^ seeds[i]) & 0x7fffffff) % width;
            sketch[i].incrementAndGet(idx);
        }
        if (totalIncrements.incrementAndGet() % resetPeriod == 0) {
            halveAll();
        }
    }

    private void halveAll() {
        for (int i = 0; i < DEPTH; i++) {
            for (int j = 0; j < width; j++) {
                sketch[i].set(j, sketch[i].get(j) / 2);
            }
        }
    }

    /** Approximate access frequency for a key. */
    public int estimate(String key) {
        long hash = mix64(key.hashCode());
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < DEPTH; i++) {
            int idx = (int) ((hash ^ seeds[i]) & 0x7fffffff) % width;
            min = Math.min(min, sketch[i].get(idx));
        }
        return min;
    }

    @Override
    public boolean admit(String key, CacheEntry entry) {
        recordAccess(key);
        return estimate(key) > cacheMinCount.getAsInt();
    }

    @Override
    public String name() {
        return "tinylfu";
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
