package com.adaptivecache.classifier;

/**
 * The three-tier workload taxonomy used by AdaptCache Pro (deck slide 4).
 */
public enum TrafficClass {

    /**
     * Steady &amp; frequent: high frequency, low arrival variance.
     * Long TTL, highest retention priority, background async revalidation.
     */
    HOT,

    /**
     * Spiky &amp; clustered: high arrival variance, sharp surges.
     * Medium TTL, RFC 5861 stale-while-revalidate serving.
     */
    BURSTY,

    /**
     * Transient / single scan: low frequency (f &lt;= 2).
     * Short TTL, lowest retention priority — evicted first.
     */
    ONE_SHOT;

    /**
     * Retention priority: higher means "keep longer".
     */
    public int priority() {
        return switch (this) {
            case HOT -> 3;
            case BURSTY -> 2;
            case ONE_SHOT -> 1;
        };
    }

    public static TrafficClass fromName(String name) {
        if (name == null) {
            return ONE_SHOT;
        }
        try {
            return TrafficClass.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ONE_SHOT;
        }
    }
}
