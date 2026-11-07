package com.adaptivecache.classifier;

import java.util.Arrays;

/**
 * Fixed-capacity circular ring buffer of epoch-millis timestamps.
 *
 * <p>One instance lives per cache key. Bounded memory: O(capacity) longs,
 * O(1) append. Oldest samples silently fall off the ring — this is the
 * "rolling observation window" from the design deck.</p>
 */
public final class RingBuffer {

    private final long[] stamps;
    private final int capacity;
    private int head;   // index of oldest element
    private int size;

    public RingBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.stamps = new long[this.capacity];
        this.head = 0;
        this.size = 0;
    }

    /** Appends a timestamp, evicting the oldest if full. */
    public synchronized void record(long timestamp) {
        if (size < capacity) {
            stamps[(head + size) % capacity] = timestamp;
            size++;
        } else {
            stamps[head] = timestamp;
            head = (head + 1) % capacity;
        }
    }

    /** Current number of stored samples. */
    public synchronized int size() {
        return size;
    }

    /** Timestamps in ascending order (snapshot copy). */
    public synchronized long[] snapshot() {
        long[] out = new long[size];
        for (int i = 0; i < size; i++) {
            out[i] = stamps[(head + i) % capacity];
        }
        return out;
    }

    /** Number of samples within {@code (now - windowMs, now]}. */
    public synchronized int countWithin(long now, long windowMs) {
        long cutoff = now - windowMs;
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (stamps[(head + i) % capacity] > cutoff) {
                count++;
            }
        }
        return count;
    }

    /** Inter-arrival deltas (ascending) for samples within the window. */
    public synchronized double[] interArrivalDeltas(long now, long windowMs) {
        long cutoff = now - windowMs;
        long[] inWindow = Arrays.stream(snapshot())
                .filter(t -> t > cutoff)
                .toArray();
        if (inWindow.length < 2) {
            return new double[0];
        }
        double[] deltas = new double[inWindow.length - 1];
        for (int i = 1; i < inWindow.length; i++) {
            deltas[i - 1] = inWindow[i] - inWindow[i - 1];
        }
        return deltas;
    }

    /** Clears all samples. */
    public synchronized void clear() {
        Arrays.fill(stamps, 0L);
        head = 0;
        size = 0;
    }

    public int capacity() {
        return capacity;
    }
}
