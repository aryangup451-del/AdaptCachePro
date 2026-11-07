package com.adaptivecache.core;

import com.adaptivecache.config.AdaptCacheConfig;
import com.adaptivecache.policy.AdmissionPolicy;
import com.adaptivecache.policy.AlwaysAdmitPolicy;
import com.adaptivecache.policy.EvictionPolicy;
import com.adaptivecache.policy.PriorityAwareLruPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Zero-dependency, thread-safe adaptive cache.
 *
 * <ul>
 *   <li>O(1) lookups via {@link ConcurrentHashMap}</li>
 *   <li>O(1) recency updates via a custom {@link DoublyLinkedList}</li>
 *   <li>Pluggable {@link EvictionPolicy} (priority-LRU, LFU, CACS)</li>
 *   <li>Pluggable {@link AdmissionPolicy} (always, TinyLFU)</li>
 *   <li>Logical {@code activeCapacity} adjustable at runtime by the
 *       self-tuning controller without rebuilding the store</li>
 * </ul>
 *
 * <p>The cache is expiry-agnostic: it stores entries until capacity forces
 * eviction. Freshness / stale serving decisions belong to the proxy pipeline.</p>
 */
public final class LRUCache {

    @FunctionalInterface
    public interface EvictionListener {
        void onEvict(CacheEntry entry);
    }

    private final int capacity;
    private final ConcurrentHashMap<String, CacheNode> map;
    private final DoublyLinkedList list;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<EvictionListener> evictionListeners = new ArrayList<>();

    private volatile EvictionPolicy evictionPolicy;
    private volatile AdmissionPolicy admissionPolicy;
    private volatile int activeCapacity;

    public LRUCache(int capacity) {
        this(capacity, new PriorityAwareLruPolicy(), new AlwaysAdmitPolicy());
    }

    public LRUCache(int capacity, EvictionPolicy evictionPolicy,
                    AdmissionPolicy admissionPolicy) {
        this.capacity = Math.max(1, capacity);
        this.map = new ConcurrentHashMap<>();
        this.list = new DoublyLinkedList();
        this.evictionPolicy = evictionPolicy;
        this.admissionPolicy = admissionPolicy;
        this.activeCapacity = this.capacity;
    }

    public void setEvictionPolicy(EvictionPolicy policy) {
        this.evictionPolicy = policy;
    }

    public void setAdmissionPolicy(AdmissionPolicy policy) {
        this.admissionPolicy = policy;
    }

    public String evictionPolicyName() {
        return evictionPolicy.name();
    }

    public String admissionPolicyName() {
        return admissionPolicy.name();
    }

    public void addEvictionListener(EvictionListener listener) {
        lock.writeLock().lock();
        try {
            evictionListeners.add(listener);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Logical capacity bound used by the eviction loop. The self-tuning
     * controller moves this within [1, physical capacity].
     */
    public void setActiveCapacity(int activeCapacity) {
        this.activeCapacity = Math.max(1, Math.min(capacity, activeCapacity));
    }

    public int activeCapacity() {
        return activeCapacity;
    }

    public CacheEntry get(String key) {
        CacheNode node = map.get(key);
        if (node == null) {
            return null;
        }
        if (lock.writeLock().tryLock()) {
            try {
                if (map.get(key) == node) {
                    list.moveToEnd(node);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        return node.entry;
    }

    public CacheEntry peek(String key) {
        CacheNode node = map.get(key);
        return node == null ? null : node.entry;
    }

    /**
     * Inserts or replaces an entry, evicting when over the active capacity.
     *
     * @return true when the entry is stored, false when the admission
     *         policy rejected it (caller still serves the fetched body once)
     */
    public boolean put(String key, CacheEntry entry) {
        lock.writeLock().lock();
        try {
            CacheNode existing = map.get(key);
            if (existing != null) {
                copyInto(existing.entry, entry);
                list.moveToEnd(existing);
                return true;
            }

            if (!admissionPolicy.admit(key, entry)) {
                return false;
            }

            CacheNode node = new CacheNode(key, entry);
            list.addLast(node);
            map.put(key, node);

            int limit = Math.max(1, activeCapacity);
            while (map.size() > limit) {
                CacheNode head = list.head();
                if (head == null) {
                    break;
                }
                CacheNode victim = evictionPolicy.selectVictim(head,
                        AdaptCacheConfig.EVICTION_SCAN_LIMIT);
                if (victim == null) {
                    break;
                }
                list.remove(victim);
                map.remove(victim.key);
                admissionPolicy.onEvict(victim.entry);
                for (EvictionListener l : evictionListeners) {
                    l.onEvict(victim.entry);
                }
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Minimum access count in the eviction scan window (TinyLFU supplier). */
    public int minAccessCountInWindow() {
        if (lock.writeLock().tryLock()) {
            try {
                CacheNode cur = list.head();
                int scanned = 0;
                long min = Long.MAX_VALUE;
                while (cur != null
                        && scanned < AdaptCacheConfig.EVICTION_SCAN_LIMIT) {
                    min = Math.min(min, cur.getEntry().getAccessCount());
                    cur = cur.getNext();
                    scanned++;
                }
                return min == Long.MAX_VALUE ? 0 : (int) Math.min(min,
                        Integer.MAX_VALUE);
            } finally {
                lock.writeLock().unlock();
            }
        }
        return 0;
    }

    public CacheEntry remove(String key) {
        lock.writeLock().lock();
        try {
            CacheNode node = map.remove(key);
            if (node == null) {
                return null;
            }
            list.remove(node);
            return node.entry;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            map.clear();
            while (list.removeFirst() != null) {
                // drain
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void copyInto(CacheEntry target, CacheEntry source) {
        target.setBody(source.getBody());
        target.setEtag(source.getEtag());
        target.setContentType(source.getContentType());
        target.setStoredAt(source.getStoredAt());
        target.setExpiresAt(source.getExpiresAt());
        target.setSwrWindowMs(source.getSwrWindowMs());
        target.setStaleIfErrorMs(source.getStaleIfErrorMs());
        target.setBackendLatencyMs(source.getBackendLatencyMs());
        target.setPredictedScore(source.getPredictedScore());
        target.setTrafficClass(source.getTrafficClass());
    }
}
