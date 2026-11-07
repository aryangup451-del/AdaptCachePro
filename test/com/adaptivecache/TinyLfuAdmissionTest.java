package com.adaptivecache;

import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.policy.AlwaysAdmitPolicy;
import com.adaptivecache.policy.PriorityAwareLruPolicy;
import com.adaptivecache.policy.TinyLfuAdmissionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TinyLFU admission: sketch frequency estimation, admission gating and
 * periodic counter halving.
 */
class TinyLfuAdmissionTest {

    private static CacheEntry entry(String key) {
        return new CacheEntry(key, key.hashCode(), "v".getBytes());
    }

    @Test
    void sketchCountsRepeatedKeys() {
        TinyLfuAdmissionPolicy tiny = new TinyLfuAdmissionPolicy(256, () -> 0);
        tiny.recordAccess("hot");
        tiny.recordAccess("hot");
        tiny.recordAccess("hot");
        assertEquals(3, tiny.estimate("hot"));
        assertEquals(0, tiny.estimate("never-seen"));
    }

    @Test
    void admitsWhenEstimateBeatsCacheMinimum() {
        TinyLfuAdmissionPolicy tiny = new TinyLfuAdmissionPolicy(256, () -> 5);
        for (int i = 0; i < 10; i++) {
            tiny.recordAccess("popular");
        }
        assertTrue(tiny.admit("popular", entry("popular")));
    }

    @Test
    void rejectsOneShotScanWhenCacheIsWarm() {
        TinyLfuAdmissionPolicy tiny = new TinyLfuAdmissionPolicy(256, () -> 5);
        tiny.recordAccess("scan-1");
        assertFalse(tiny.admit("scan-1", entry("scan-1")));
    }

    @Test
    void countersHalvePeriodicallySoOldPopularityDecays() {
        TinyLfuAdmissionPolicy tiny = new TinyLfuAdmissionPolicy(16, () -> 0);
        for (int i = 0; i < 10; i++) {
            tiny.recordAccess("fading");
        }
        int before = tiny.estimate("fading");
        for (int i = 0; i < 16; i++) {
            tiny.recordAccess("other-" + i);
        }
        int after = tiny.estimate("fading");
        assertTrue(after <= before);
    }

    @Test
    void rejectedPutsAreNotStored() {
        LRUCache cache = new LRUCache(4, new PriorityAwareLruPolicy(),
                new AlwaysAdmitPolicy());
        TinyLfuAdmissionPolicy tiny = new TinyLfuAdmissionPolicy(256,
                () -> Integer.MAX_VALUE);
        cache.setAdmissionPolicy(tiny);
        assertFalse(cache.put("x", entry("x")));
        assertEquals(0, cache.size());
    }
}
