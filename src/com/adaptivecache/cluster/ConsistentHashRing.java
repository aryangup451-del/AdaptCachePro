package com.adaptivecache.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Consistent-hash ring for the proxy cluster (Item 8). Keys map to owning
 * nodes; adding or removing a node remaps only ~1/N keys.
 */
public final class ConsistentHashRing {

    private final SortedMap<Long, String> ring = new TreeMap<>();
    private final int replicas;

    public ConsistentHashRing(List<String> nodes, int replicas) {
        this.replicas = Math.max(1, replicas);
        for (String node : nodes) {
            addNode(node);
        }
    }

    public synchronized void addNode(String node) {
        for (int i = 0; i < replicas; i++) {
            ring.put(hash(node + "#" + i), node);
        }
    }

    public synchronized void removeNode(String node) {
        for (int i = 0; i < replicas; i++) {
            ring.remove(hash(node + "#" + i));
        }
    }

    public synchronized String ownerOf(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        long h = hash(key);
        SortedMap<Long, String> tail = ring.tailMap(h);
        long nodeHash = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(nodeHash);
    }

    public synchronized int nodeCount() {
        List<String> distinct = new ArrayList<>(ring.values());
        return (int) distinct.stream().distinct().count();
    }

    public synchronized boolean isEmpty() {
        return ring.isEmpty();
    }

    static long hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xffL);
            }
            return h & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            return s.hashCode() & 0x7fffffffL;
        }
    }
}
