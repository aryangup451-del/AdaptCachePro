package com.adaptivecache;

import com.adaptivecache.cluster.ConsistentHashRing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consistent-hash ring: full key coverage, balanced spread and minimal
 * remapping when nodes join/leave.
 */
class ConsistentHashRingTest {

    @Test
    void everyKeyMapsToANode() {
        ConsistentHashRing ring = new ConsistentHashRing(
                List.of("a", "b", "c"), 64);
        for (int i = 0; i < 200; i++) {
            assertNotNull(ring.ownerOf("key-" + i));
        }
        assertEquals(3, ring.nodeCount());
    }

    @Test
    void keysSpreadAcrossAllNodes() {
        ConsistentHashRing ring = new ConsistentHashRing(
                List.of("a", "b", "c"), 128);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            counts.merge(ring.ownerOf("key-" + i), 1, Integer::sum);
        }
        assertEquals(3, counts.size());
        for (int c : counts.values()) {
            assertTrue(c > 500, "balanced spread, got " + counts);
        }
    }

    @Test
    void nodeRemovalRemapsOnlyFractionOfKeys() {
        ConsistentHashRing ring = new ConsistentHashRing(
                List.of("a", "b", "c"), 128);
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            before.put("key-" + i, ring.ownerOf("key-" + i));
        }
        ring.removeNode("c");
        int remapped = 0;
        for (int i = 0; i < 3000; i++) {
            if (!before.get("key-" + i).equals(ring.ownerOf("key-" + i))) {
                remapped++;
            }
        }
        assertTrue(remapped > 0);
        assertTrue(remapped < 1500, "remapped=" + remapped);
    }

    @Test
    void emptyRingReturnsNull() {
        ConsistentHashRing ring = new ConsistentHashRing(List.of(), 16);
        assertTrue(ring.isEmpty());
        assertEquals(null, ring.ownerOf("k"));
    }
}
