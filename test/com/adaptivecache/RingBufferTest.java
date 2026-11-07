package com.adaptivecache;

import com.adaptivecache.classifier.RingBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bounded circular ring buffer: O(1) append, strict capacity, windowed
 * counting and inter-arrival delta extraction.
 */
class RingBufferTest {

    @Test
    void recordsInAscendingOrder() {
        RingBuffer ring = new RingBuffer(8);
        ring.record(10);
        ring.record(20);
        ring.record(30);
        assertArrayEquals(new long[]{10, 20, 30}, ring.snapshot());
        assertEquals(3, ring.size());
    }

    @Test
    void respectsCapacityWithOldestEvictedFirst() {
        RingBuffer ring = new RingBuffer(4);
        for (long t = 1; t <= 10; t++) {
            ring.record(t);
        }
        assertEquals(4, ring.size());
        assertArrayEquals(new long[]{7, 8, 9, 10}, ring.snapshot());
    }

    @Test
    void countsSamplesWithinWindow() {
        RingBuffer ring = new RingBuffer(16);
        ring.record(100);
        ring.record(200);
        ring.record(300);
        ring.record(400);
        assertEquals(3, ring.countWithin(350, 250)); // >100 => 200,300
        assertEquals(4, ring.countWithin(400, 400));
        assertEquals(0, ring.countWithin(1000, 50));
    }

    @Test
    void interArrivalDeltasAreCorrect() {
        RingBuffer ring = new RingBuffer(8);
        ring.record(100);
        ring.record(150);
        ring.record(210);
        double[] deltas = ring.interArrivalDeltas(210, 200);
        assertArrayEquals(new double[]{50.0, 60.0}, deltas);
    }

    @Test
    void clearEmptiesBuffer() {
        RingBuffer ring = new RingBuffer(4);
        ring.record(1);
        ring.record(2);
        ring.clear();
        assertEquals(0, ring.size());
        assertEquals(0, ring.countWithin(2, 100));
    }

    @Test
    void emptyBufferHasNoDeltas() {
        RingBuffer ring = new RingBuffer(4);
        assertEquals(0, ring.interArrivalDeltas(1000, 1000).length);
    }
}
