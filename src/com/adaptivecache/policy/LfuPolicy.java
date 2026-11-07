package com.adaptivecache.policy;

import com.adaptivecache.core.CacheNode;

/**
 * Classic LFU victim selection: evict the least-frequently-used entry among
 * the scanned window (ties broken by LRU order).
 */
public final class LfuPolicy implements EvictionPolicy {

    @Override
    public CacheNode selectVictim(CacheNode head, int scanLimit) {
        CacheNode victim = head;
        long best = head.getEntry().getAccessCount();

        CacheNode cur = head.getNext();
        int scanned = 1;
        while (cur != null && scanned < scanLimit) {
            long c = cur.getEntry().getAccessCount();
            if (c < best) {
                best = c;
                victim = cur;
            }
            cur = cur.getNext();
            scanned++;
        }
        return victim;
    }

    @Override
    public String name() {
        return "lfu";
    }
}
