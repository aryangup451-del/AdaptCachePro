package com.adaptivecache;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom LRU cache: eviction order, capacity bound, priority-aware eviction
 * and concurrent access safety.
 */
class LRUCacheTest {

    private static CacheEntry entry(String key) {
        return new CacheEntry(key, key.hashCode(),
                ("value-of-" + key).getBytes());
    }

    @Test
    void evictsLeastRecentlyUsed() {
        LRUCache cache = new LRUCache(3);
        cache.put("a", entry("a"));
        cache.put("b", entry("b"));
        cache.put("c", entry("c"));

        cache.get("a");            // a is now most recent
        cache.put("d", entry("d")); // capacity exceeded -> evict LRU = b

        assertNull(cache.get("b"));
        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("c"));
        assertNotNull(cache.get("d"));
        assertEquals(3, cache.size());
    }

    @Test
    void updatingExistingEntryDoesNotGrowCache() {
        LRUCache cache = new LRUCache(3);
        cache.put("a", entry("a"));
        cache.put("a", entry("a"));
        assertEquals(1, cache.size());
    }

    @Test
    void capacityIsNeverExceeded() {
        LRUCache cache = new LRUCache(5);
        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, entry("k" + i));
        }
        assertTrue(cache.size() <= 5);
        assertEquals(5, cache.size());
    }

    @Test
    void getMovesEntryToMostRecentEnd() {
        LRUCache cache = new LRUCache(2);
        cache.put("a", entry("a"));
        cache.put("b", entry("b"));
        cache.get("a");
        cache.put("c", entry("c"));
        assertNull(cache.get("b"));
        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("c"));
    }

    @Test
    void evictionPrefersLowPriorityVictim() {
        LRUCache cache = new LRUCache(3);

        CacheEntry hot = entry("hot");
        hot.setTrafficClass(TrafficClass.HOT);          // priority 3
        CacheEntry bursty = entry("bursty");
        bursty.setTrafficClass(TrafficClass.BURSTY);    // priority 2
        CacheEntry oneShot = entry("oneshot");
        oneShot.setTrafficClass(TrafficClass.ONE_SHOT); // priority 1

        cache.put("hot", hot);
        cache.put("bursty", bursty);
        cache.put("oneshot", oneShot);

        // LRU order: hot(1st) -> bursty -> oneshot(tail).
        // New insert forces eviction: the LRU head is HOT, but the
        // lowest-priority ONE_SHOT victim must be chosen instead.
        cache.put("new", entry("new"));

        assertNotNull(cache.get("hot"));
        assertNotNull(cache.get("bursty"));
        assertNull(cache.get("oneshot"));
        assertNotNull(cache.get("new"));
    }

    @Test
    void evictionListenerFiresOnEviction() {
        LRUCache cache = new LRUCache(2);
        List<String> evicted = new ArrayList<>();
        cache.addEvictionListener(e -> evicted.add(e.getKey()));
        cache.put("a", entry("a"));
        cache.put("b", entry("b"));
        cache.put("c", entry("c"));
        assertEquals(List.of("a"), evicted);
    }

    @Test
    void concurrentAccessIsSafe() throws InterruptedException {
        LRUCache cache = new LRUCache(64);
        int threads = 16;
        int opsPerThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "key-" + ((i + tid) % 128);
                        if (i % 2 == 0) {
                            cache.put(key, entry(key));
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertTrue(cache.size() <= 64);
        assertEquals(cache.capacity(), 64);
    }

    @Test
    void removeDetachesEntry() {
        LRUCache cache = new LRUCache(3);
        cache.put("a", entry("a"));
        cache.put("b", entry("b"));
        assertNotNull(cache.remove("a"));
        assertNull(cache.get("a"));
        assertEquals(1, cache.size());
        cache.put("c", entry("c"));
        cache.put("d", entry("d"));
        assertNotNull(cache.get("b"));
    }
}
