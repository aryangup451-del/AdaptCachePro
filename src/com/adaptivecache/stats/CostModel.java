package com.adaptivecache.stats;

import com.adaptivecache.config.AdaptCacheConfig;

/**
 * Quantifies the financial value of caching (deck slide 8).
 *
 * <p>Two views are maintained:</p>
 * <ol>
 *   <li><b>Exact accounting</b> — every cache-served response credits the
 *       avoided origin compute cost plus the avoided egress bandwidth cost of
 *       its body.</li>
 *   <li><b>Aggregate formula</b> — the deck's headline equation:
 *       {@code Cost Saved = (Total Requests × Hit Ratio) × (Cost_Compute +
 *       Cost_Bandwidth_Egress)} using the average response size.</li>
 * </ol>
 */
public final class CostModel {

    private final double egressCostPerByte;
    private final double originComputeCostPerRequest;
    private final long avgResponseBytes;

    // Exact accounting (monotonic counters)
    private double exactSavings = 0.0;
    private long cacheServedRequests = 0;
    private long cacheServedBytes = 0;

    public CostModel() {
        this(AdaptCacheConfig.EGRESS_COST_PER_GB,
                AdaptCacheConfig.ORIGIN_COMPUTE_COST_PER_REQUEST,
                AdaptCacheConfig.AVG_RESPONSE_BYTES);
    }

    public CostModel(double egressCostPerGb,
                     double originComputeCostPerRequest,
                     long avgResponseBytes) {
        this.egressCostPerByte = egressCostPerGb / 1_000_000_000.0;
        this.originComputeCostPerRequest = originComputeCostPerRequest;
        this.avgResponseBytes = avgResponseBytes;
    }

    /**
     * Credits one cache-served response of {@code bodyBytes} bytes.
     * Thread-safe: counters are guarded by the instance monitor.
     */
    public synchronized void recordCacheServe(long bodyBytes) {
        double egress = bodyBytes * egressCostPerByte;
        exactSavings += originComputeCostPerRequest + egress;
        cacheServedRequests++;
        cacheServedBytes += bodyBytes;
    }

    /** Applies the deck's aggregate formula for a reporting snapshot. */
    public synchronized Report report(long totalRequests, double hitRatio) {
        double avgUnitCost = originComputeCostPerRequest
                + (avgResponseBytes * egressCostPerByte);
        double aggregate = totalRequests * hitRatio * avgUnitCost;
        return new Report(totalRequests, hitRatio, exactSavings, aggregate,
                cacheServedRequests, cacheServedBytes,
                originComputeCostPerRequest, egressCostPerByte);
    }

    /** Immutable cost snapshot. */
    public record Report(
            long totalRequests,
            double hitRatio,
            double exactSavingsUsd,
            double aggregateSavingsUsd,
            long cacheServedRequests,
            long cacheServedBytes,
            double computeCostPerRequest,
            double egressCostPerByte) {

        /** Savings as a percentage of "all requests went to origin". */
        public double savingsPercentOfNoCache() {
            double avgBytes = cacheServedRequests > 0
                    ? (double) cacheServedBytes / cacheServedRequests
                    : 0.0;
            double noCacheCost = totalRequests
                    * (computeCostPerRequest + avgBytes * egressCostPerByte);
            if (noCacheCost <= 0) {
                return 0.0;
            }
            return Math.min(100.0, 100.0 * exactSavingsUsd / noCacheCost);
        }
    }
}
