package com.adaptivecache;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.policy.CacsPolicy;
import com.adaptivecache.policy.LfuPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Novel CACS eviction and LFU victim selection.
 */
class CacsPolicyTest {

    private static CacheEntry entry(String key, TrafficClass clazz,
                                    int serves, long backendMs,
                                    double predicted, int size) {
        byte[] body = new byte[size];
        CacheEntry e = new CacheEntry(key, key.hashCode(), body);
        e.setTrafficClass(clazz);
        e.setBackendLatencyMs(backendMs);
        e.setPredictedScore(predicted);
        for (int i = 0; i < serves; i++) {
            e.bumpAccessCount();
        }
        return e;
    }

    @Test
    void scoreIsBounded() {
        CacsPolicy cacs = new CacsPolicy();
        CacheEntry e = entry("k", TrafficClass.HOT, 50, 200, 0.9, 256);
        double s = cacs.score(e, System.currentTimeMillis(), 60_000L, 0);
        assertTrue(s >= 0.0 && s <= 1.0 + 1e-9);
    }

    @Test
    void hotValuableEntryOutscoresColdOneShot() {
        CacsPolicy cacs = new CacsPolicy();
        long now = System.currentTimeMillis();
        CacheEntry hot = entry("hot", TrafficClass.HOT, 100, 240, 0.9, 256);
        CacheEntry cold = entry("cold", TrafficClass.ONE_SHOT, 1, 5, 0.0,
                64 * 1024);
        assertTrue(cacs.score(hot, now, 60_000L, 5)
                > cacs.score(cold, now, 60_000L, 0));
    }

    @Test
    void cacsEvictsLowestValueVictim() {
        LRUCache cache = new LRUCache(3, new CacsPolicy(),
                new com.adaptivecache.policy.AlwaysAdmitPolicy());
        cache.put("hot", entry("hot", TrafficClass.HOT, 200, 240, 0.95, 256));
        cache.put("mid", entry("mid", TrafficClass.BURSTY, 10, 120, 0.4,
                1024));
        cache.put("junk", entry("junk", TrafficClass.ONE_SHOT, 1, 2, 0.0,
                1024));
        cache.put("new", entry("new", TrafficClass.ONE_SHOT, 1, 5, 0.0, 512));

        assertNotNull(cache.get("hot"));
        assertNull(cache.get("junk"));
        assertEquals(3, cache.size());
    }

    @Test
    void lfuEvictsLeastFrequentlyUsed() {
        LRUCache cache = new LRUCache(2, new LfuPolicy(),
                new com.adaptivecache.policy.AlwaysAdmitPolicy());
        CacheEntry often = entry("often", TrafficClass.HOT, 50, 10, 0.5, 64);
        CacheEntry rare = entry("rare", TrafficClass.HOT, 1, 10, 0.5, 64);
        cache.put("often", often);
        cache.put("rare", rare);
        cache.get("often");
        cache.put("third", entry("third", TrafficClass.HOT, 1, 10, 0.5, 64));

        assertNotNull(cache.get("often"));
        assertNull(cache.get("rare"));
    }

    @Test
    void policyNamesAreStable() {
        assertEquals("cacs", new CacsPolicy().name());
        assertEquals("lfu", new LfuPolicy().name());
    }
}
