package com.adaptivecache.config;

/**
 * Central configuration for AdaptCache Pro.
 *
 * <p>All tunables live here. Every value can be overridden at runtime through
 * environment variables so the same binary works for demos, tests and CI.</p>
 */
public final class AdaptCacheConfig {

    private AdaptCacheConfig() {
    }

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    /** Port of the reverse proxy. */
    public static final int PROXY_PORT = intEnv("PROXY_PORT", 8080);

    /** Port of the origin server. */
    public static final int ORIGIN_PORT = intEnv("ORIGIN_PORT", 9000);

    /** Upstream base URL of the origin server. */
    public static final String ORIGIN_BASE_URL =
            strEnv("ORIGIN_BASE_URL", "http://localhost:" + ORIGIN_PORT);

    /** Connect/request timeout for upstream calls (millis). */
    public static final long UPSTREAM_TIMEOUT_MS =
            longEnv("UPSTREAM_TIMEOUT_MS", 2_000L);

    // ---------------------------------------------------------------------
    // Cache engine
    // ---------------------------------------------------------------------

    /** Maximum number of entries held by the LRU cache. */
    public static final int CACHE_CAPACITY = intEnv("CACHE_CAPACITY", 1_024);

    /** How many list nodes to scan while hunting a lower-priority victim. */
    public static final int EVICTION_SCAN_LIMIT = 32;

    // ---------------------------------------------------------------------
    // Classification engine
    // ---------------------------------------------------------------------

    /** Rolling observation window used for frequency / variance (millis). */
    public static final long OBSERVATION_WINDOW_MS =
            longEnv("OBSERVATION_WINDOW_MS", 60_000L);

    /** Fixed capacity of the per-key circular ring buffer. */
    public static final int RING_BUFFER_CAPACITY = intEnv("RING_BUFFER_CAPACITY", 256);

    /** Minimum samples inside the window before a key becomes "warm". */
    public static final int MIN_SAMPLES_TO_CLASSIFY =
            intEnv("MIN_SAMPLES_TO_CLASSIFY", 3);

    /**
     * Frequency threshold (requests/minute) above which a steady key is HOT.
     */
    public static final double HOT_MIN_RATE_PER_MIN =
            doubleEnv("HOT_MIN_RATE_PER_MIN", 5.0);

    /**
     * Coefficient-of-variation threshold. A key whose inter-arrival CV is at
     * or above this value is considered spiky (BURSTY).
     */
    public static final double BURSTY_CV_THRESHOLD =
            doubleEnv("BURSTY_CV_THRESHOLD", 0.8);

    /** Keys with at most this many lifetime requests are ONE_SHOT. */
    public static final int ONE_SHOT_MAX_FREQUENCY =
            intEnv("ONE_SHOT_MAX_FREQUENCY", 2);

    // ---------------------------------------------------------------------
    // Adaptive policy engine (TTLs in seconds — deck slide 4)
    // ---------------------------------------------------------------------

    public static final long HOT_TTL_SECONDS = longEnv("HOT_TTL_SECONDS", 300L);
    public static final long BURSTY_TTL_SECONDS = longEnv("BURSTY_TTL_SECONDS", 60L);
    public static final long ONE_SHOT_TTL_SECONDS = longEnv("ONE_SHOT_TTL_SECONDS", 10L);

    /** RFC 5861 stale-while-revalidate window for bursty keys (seconds). */
    public static final long BURSTY_SWR_SECONDS =
            longEnv("BURSTY_SWR_SECONDS", 60L);

    /** RFC 5861 stale-if-error window applied to every cached entry (seconds). */
    public static final long STALE_IF_ERROR_SECONDS =
            longEnv("STALE_IF_ERROR_SECONDS", 3_600L);

    // ---------------------------------------------------------------------
    // Cost model ($)
    // ---------------------------------------------------------------------

    /** Cloud egress cost per GB (deck: $0.08-$0.12/GB). */
    public static final double EGRESS_COST_PER_GB =
            doubleEnv("EGRESS_COST_PER_GB", 0.08);

    /** Estimated origin compute cost per forwarded request ($). */
    public static final double ORIGIN_COMPUTE_COST_PER_REQUEST =
            doubleEnv("ORIGIN_COMPUTE_COST_PER_REQUEST", 0.00002);

    /** Average cached response size used by the aggregate formula (bytes). */
    public static final long AVG_RESPONSE_BYTES =
            longEnv("AVG_RESPONSE_BYTES", 64 * 1024L);

    // ---------------------------------------------------------------------
    // Telemetry
    // ---------------------------------------------------------------------

    /** Cap on per-key stat records to bound memory. */
    public static final int MAX_TRACKED_KEYS = intEnv("MAX_TRACKED_KEYS", 10_000);

    // ---------------------------------------------------------------------
    // Research extensions: eviction / admission selection
    // ---------------------------------------------------------------------

    /** Eviction policy: priority-lru (default), lfu, cacs. */
    public static final String EVICTION_POLICY =
            strEnv("EVICTION_POLICY", "priority-lru");

    /** Admission policy: always (default), tinylfu. */
    public static final String ADMISSION_POLICY =
            strEnv("ADMISSION_POLICY", "always");

    /** Sketch width for the TinyLFU admission filter. */
    public static final int TINYLFU_WIDTH = intEnv("TINYLFU_WIDTH", 4096);

    // ---------------------------------------------------------------------
    // Research extensions: cluster / multi-level
    // ---------------------------------------------------------------------

    /** Stable identity of this proxy node (default: host:port). */
    public static final String NODE_ID = strEnv("NODE_ID", "");

    /** Comma-separated peer proxy base URLs for consistent-hash routing. */
    public static final String PEER_PROXIES = strEnv("PEER_PROXIES", "");

    /** Virtual nodes per cluster member on the hash ring. */
    public static final int HASH_RING_REPLICAS =
            intEnv("HASH_RING_REPLICAS", 128);

    // ---------------------------------------------------------------------
    // Research extensions: self-tuning + warming + prediction
    // ---------------------------------------------------------------------

    /** Master switch for the self-tuning controller. */
    public static final boolean SELF_TUNING_ENABLED =
            boolEnv("SELF_TUNING_ENABLED", false);

    /** Hit-ratio target the tuner steers toward. */
    public static final double TUNER_TARGET_HIT_RATIO =
            doubleEnv("TUNER_TARGET_HIT_RATIO", 0.85);

    /** Tuner control interval in seconds. */
    public static final long TUNER_INTERVAL_SECONDS =
            longEnv("TUNER_INTERVAL_SECONDS", 10L);

    /** Number of top predicted keys pre-fetched on boot / on demand. */
    public static final int WARM_TOP_K = intEnv("WARM_TOP_K", 50);

    /** EWMA smoothing factor for the demand predictor. */
    public static final double PREDICTOR_ALPHA =
            doubleEnv("PREDICTOR_ALPHA", 0.3);

    // ---------------------------------------------------------------------
    // Research extensions: green computing
    // ---------------------------------------------------------------------

    /** Assumed node thermal design power in watts for energy estimates. */
    public static final double NODE_TDP_WATTS =
            doubleEnv("NODE_TDP_WATTS", 65.0);

    /** Grid carbon intensity in grams CO2 per kWh. */
    public static final double GRID_CO2_G_PER_KWH =
            doubleEnv("GRID_CO2_G_PER_KWH", 400.0);

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static int intEnv(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[config] bad int for " + name + "='" + v
                    + "', using default " + def);
            return def;
        }
    }

    private static long longEnv(String name, long def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[config] bad long for " + name + "='" + v
                    + "', using default " + def);
            return def;
        }
    }

    private static double doubleEnv(String name, double def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[config] bad double for " + name + "='" + v
                    + "', using default " + def);
            return def;
        }
    }

    private static String strEnv(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static boolean boolEnv(String name, boolean def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        return v.trim().equalsIgnoreCase("true")
                || v.trim().equals("1");
    }
}
