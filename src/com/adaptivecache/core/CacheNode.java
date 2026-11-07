package com.adaptivecache.core;

/**
 * A node of the custom doubly linked list used by the LRU cache.
 *
 * <p>Not thread-safe by itself — the owning {@link LRUCache} guards every
 * mutation with its write lock.</p>
 */
public final class CacheNode {

    final String key;
    final CacheEntry entry;

    CacheNode prev;
    CacheNode next;

    public CacheNode(String key, CacheEntry entry) {
        this.key = key;
        this.entry = entry;
    }

    public String getKey() {
        return key;
    }

    public CacheEntry getEntry() {
        return entry;
    }

    public CacheNode getNext() {
        return next;
    }
}
