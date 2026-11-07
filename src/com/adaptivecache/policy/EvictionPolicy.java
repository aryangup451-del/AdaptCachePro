package com.adaptivecache.policy;

import com.adaptivecache.core.CacheNode;

/**
 * Pluggable victim-selection strategy for cache eviction.
 * Implementations must be side-effect free: they choose, the cache removes.
 */
public interface EvictionPolicy {

    /**
     * Selects the eviction victim by scanning from the LRU head.
     *
     * @param head      least-recently-used node (never null when called)
     * @param scanLimit maximum nodes to consider from the head
     * @return the node to evict (must be one of the scanned nodes)
     */
    CacheNode selectVictim(CacheNode head, int scanLimit);

    /** Short name for logs / telemetry (e.g. "cacs"). */
    String name();
}
