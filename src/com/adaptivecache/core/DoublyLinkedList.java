package com.adaptivecache.core;

/**
 * Custom doubly linked list backing the LRU ordering.
 *
 * <p>Head = least recently used, tail = most recently used.
 * Not thread-safe: the owning {@link LRUCache} serializes all mutations with
 * its write lock.</p>
 */
public final class DoublyLinkedList {

    private CacheNode head;
    private CacheNode tail;

    /** Number of nodes currently linked. */
    private int size;

    public int size() {
        return size;
    }

    public CacheNode head() {
        return head;
    }

    /** Append node at the tail (most recently used end). */
    public void addLast(CacheNode node) {
        if (node == null) {
            return;
        }
        if (tail == null) {
            head = tail = node;
            size = 1;
            return;
        }
        tail.next = node;
        node.prev = tail;
        tail = node;
        size++;
    }

    /** Unlink a node (idempotent — safe on already-removed nodes). */
    public void remove(CacheNode node) {
        if (node == null || (node.prev == null && node.next == null
                && head != node && tail != node)) {
            return;
        }

        if (node.prev != null) {
            node.prev.next = node.next;
        } else if (head == node) {
            head = node.next;
        }

        if (node.next != null) {
            node.next.prev = node.prev;
        } else if (tail == node) {
            tail = node.prev;
        }

        node.prev = null;
        node.next = null;
        size--;
    }

    /** Move node to the tail (mark as most recently used). */
    public void moveToEnd(CacheNode node) {
        if (node == null || node == tail) {
            return;
        }
        remove(node);
        addLast(node);
    }

    /** Remove and return the head (least recently used node). */
    public CacheNode removeFirst() {
        if (head == null) {
            return null;
        }
        CacheNode first = head;
        remove(first);
        return first;
    }

    /**
     * Remove and return the lowest-priority node among the first
     * {@code scanLimit} nodes from the head. If all scanned nodes share the
     * minimum priority, the plain LRU head is returned.
     *
     * @return the evicted node, or {@code null} if the list is empty
     */
    public CacheNode removeLowestPriorityFirst(int scanLimit) {
        if (head == null) {
            return null;
        }

        CacheNode victim = head;
        int best = victim.entry.getPriority();

        CacheNode cur = head.next;
        int scanned = 1;
        while (cur != null && scanned < scanLimit) {
            int p = cur.entry.getPriority();
            if (p < best) {
                best = p;
                victim = cur;
            }
            cur = cur.next;
            scanned++;
        }

        remove(victim);
        return victim;
    }
}
