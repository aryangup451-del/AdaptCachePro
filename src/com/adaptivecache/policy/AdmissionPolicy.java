package com.adaptivecache.policy;

import com.adaptivecache.core.CacheEntry;

/**
 * Pluggable admission filter: decides whether a freshly fetched response
 * may enter the cache at all (cf. TinyLFU admission).
 */
public interface AdmissionPolicy {

    /**
     * @param key   canonical cache key
     * @param entry the freshly fetched entry (not yet stored)
     * @return true to admit, false to serve once without storing
     */
    boolean admit(String key, CacheEntry entry);

    /** Notified when an entry leaves the cache (for filter housekeeping). */
    default void onEvict(CacheEntry entry) {
    }

    String name();
}
