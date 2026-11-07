package com.adaptivecache.policy;

import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.CacheNode;

/**
 * Context-Aware Adaptive Cache Scoring (CACS) — the project's novel
 * eviction policy.
 *
 * <p>Instead of recency or frequency alone, every candidate is scored from
 * six normalised context signals. Higher score = more valuable = retained.
 * The scan window's lowest-scoring entry is evicted:</p>
 *
 * <pre>
 * score = 0.25·frequency + 0.20·recency + 0.20·backendLatencySaved
 *       + 0.15·predictedPopularity + 0.10·sizeEfficiency + 0.10·stability
 * </pre>
 *
 * <ul>
 *   <li>frequency — log-scaled access count within the window</li>
 *   <li>recency — 1 when just touched, decaying to 0 at the scan horizon</li>
 *   <li>backendLatencySaved — normalised origin latency this entry avoids</li>
 *   <li>predictedPopularity — EWMA demand forecast, already normalised</li>
 *   <li>sizeEfficiency — small entries win ties (more objects per byte)</li>
 *   <li>stability — HOT class bonus (steady traffic is retention-worthy)</li>
 * </ul>
 */
public final class CacsPolicy implements EvictionPolicy {

    private final double wFrequency;
    private final double wRecency;
    private final double wLatency;
    private final double wPrediction;
    private final double wSize;
    private final double wStability;

    /** Reference backend latency (ms) used to normalise latency savings. */
    private final double referenceLatencyMs;

    /** Reference object size (bytes) used to normalise size efficiency. */
    private final double referenceSizeBytes;

    public CacsPolicy() {
        this(0.25, 0.20, 0.20, 0.15, 0.10, 0.10, 250.0, 64 * 1024.0);
    }

    public CacsPolicy(double wFrequency, double wRecency, double wLatency,
                      double wPrediction, double wSize, double wStability,
                      double referenceLatencyMs, double referenceSizeBytes) {
        this.wFrequency = wFrequency;
        this.wRecency = wRecency;
        this.wLatency = wLatency;
        this.wPrediction = wPrediction;
        this.wSize = wSize;
        this.wStability = wStability;
        this.referenceLatencyMs = referenceLatencyMs;
        this.referenceSizeBytes = referenceSizeBytes;
    }

    @Override
    public CacheNode selectVictim(CacheNode head, int scanLimit) {
        long now = System.currentTimeMillis();

        java.util.List<CacheNode> window = new java.util.ArrayList<>();
        CacheNode cur = head;
        while (cur != null && window.size() < scanLimit) {
            window.add(cur);
            cur = cur.getNext();
        }
        long maxAge = 1;
        for (CacheNode n : window) {
            maxAge = Math.max(maxAge, now - n.getEntry().getStoredAt());
        }

        CacheNode victim = head;
        double worst = score(head.getEntry(), now, maxAge, 0);
        int position = 0;
        for (CacheNode n : window) {
            double s = score(n.getEntry(), now, maxAge, position);
            if (s < worst) {
                worst = s;
                victim = n;
            }
            position++;
        }
        return victim;
    }

    /**
     * CACS score in [0,1]. Visible for tests and the decision explainer.
     */
    public double score(CacheEntry entry, long now, long horizonMs,
                        int lruPosition) {
        double frequency = Math.min(1.0,
                Math.log1p(entry.getAccessCount()) / Math.log1p(100.0));

        double ageMs = Math.max(0, now - entry.getStoredAt());
        double recency = horizonMs <= 0 ? 1.0
                : Math.max(0.0, 1.0 - ageMs / (double) horizonMs);

        double latency = Math.min(1.0,
                entry.getBackendLatencyMs() / referenceLatencyMs);

        double prediction = Math.max(0.0,
                Math.min(1.0, entry.getPredictedScore()));

        double sizeBytes = Math.max(1, entry.getBody() == null
                ? 1 : entry.getBody().length);
        double size = Math.max(0.0,
                Math.min(1.0, 1.0 - sizeBytes / (4 * referenceSizeBytes)));

        double stability = switch (entry.getTrafficClass()) {
            case HOT -> 1.0;
            case BURSTY -> 0.5;
            case ONE_SHOT -> 0.0;
        };

        return wFrequency * frequency
                + wRecency * recency
                + wLatency * latency
                + wPrediction * prediction
                + wSize * size
                + wStability * stability;
    }

    @Override
    public String name() {
        return "cacs";
    }
}
