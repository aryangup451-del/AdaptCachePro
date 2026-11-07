package com.adaptivecache.policy;

import com.adaptivecache.core.CacheNode;

/**
 * Default policy: lowest retention priority first, plain LRU within a
 * priority tier. Hot entries survive one-shot scans (no cache thrashing).
 */
public final class PriorityAwareLruPolicy implements EvictionPolicy {

    @Override
    public CacheNode selectVictim(CacheNode head, int scanLimit) {
        CacheNode victim = head;
        int best = head.getEntry().getPriority();

        CacheNode cur = head.getNext();
        int scanned = 1;
        while (cur != null && scanned < scanLimit) {
            int p = cur.getEntry().getPriority();
            if (p < best) {
                best = p;
                victim = cur;
            }
            cur = cur.getNext();
            scanned++;
        }
        return victim;
    }

    @Override
    public String name() {
        return "priority-lru";
    }
}
