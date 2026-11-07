package com.adaptivecache.telemetry;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.classifier.TrafficClassifier;
import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.explain.DecisionExplainer;
import com.adaptivecache.green.EnergyMonitor;
import com.adaptivecache.predict.EwmaPredictor;
import com.adaptivecache.proxy.ProxyServer;
import com.adaptivecache.stats.CostModel;
import com.adaptivecache.stats.RequestStats;
import com.adaptivecache.stats.RequestStatsRegistry;
import com.adaptivecache.tuning.SelfTuningController;
import com.adaptivecache.warming.WarmingScheduler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Telemetry endpoints mounted on the proxy: {@code /_health}, {@code /stats}
 * (human-readable) and {@code /metrics} (Prometheus text exposition format).
 */
public final class TelemetryEndpoints {

    private final ProxyServer proxy;
    private final RequestStatsRegistry stats;
    private final CostModel costModel;
    private final TrafficClassifier classifier;
    private final LRUCache cache;

    private EwmaPredictor predictor;
    private DecisionExplainer explainer;
    private EnergyMonitor energy;
    private SelfTuningController tuner;
    private WarmingScheduler warmer;

    public TelemetryEndpoints(ProxyServer proxy, RequestStatsRegistry stats,
                              CostModel costModel,
                              TrafficClassifier classifier, LRUCache cache) {
        this.proxy = proxy;
        this.stats = stats;
        this.costModel = costModel;
        this.classifier = classifier;
        this.cache = cache;
    }

    public void setPredictor(EwmaPredictor predictor) {
        this.predictor = predictor;
    }

    public void setExplainer(DecisionExplainer explainer) {
        this.explainer = explainer;
    }

    public void setEnergy(EnergyMonitor energy) {
        this.energy = energy;
    }

    public void setTuner(SelfTuningController tuner) {
        this.tuner = tuner;
    }

    public void setWarmer(WarmingScheduler warmer) {
        this.warmer = warmer;
    }

    public boolean handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/_explain")) {
            return consume(exchange, explainPage(exchange));
        }
        if (path.equals("/_warm")) {
            return consume(exchange, warmPage());
        }
        return switch (path) {
            case "/_health" -> consume(exchange, health());
            case "/stats" -> consume(exchange, statsPage());
            case "/metrics" -> consume(exchange, metricsPage());
            default -> false;
        };
    }

    private String explainPage(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        String target = null;
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("path")) {
                    target = java.net.URLDecoder.decode(
                            pair.substring(eq + 1),
                            StandardCharsets.UTF_8);
                }
            }
        }
        if (target == null || target.isBlank()) {
            return "Usage: GET /_explain?path=/hot/item-1\n";
        }
        String key = "GET " + target;
        CacheEntry entry = cache.peek(key);
        if (entry == null) {
            return "No cached entry for '" + key + "'.\n";
        }
        RequestStats rs = stats.snapshot().stream()
                .filter(s -> s.getKey().equals(key))
                .findFirst().orElse(null);
        StringBuilder sb = new StringBuilder();
        sb.append("Why '").append(key).append("' is retained:\n");
        if (explainer != null) {
            for (String reason : explainer.explainRetention(entry, rs)) {
                sb.append("  - ").append(reason).append('\n');
            }
        } else {
            sb.append("  - class=").append(entry.getTrafficClass())
                    .append(", serves=").append(entry.getAccessCount())
                    .append('\n');
        }
        return sb.toString();
    }

    private String warmPage() {
        if (warmer == null) {
            return "Warming scheduler not attached.\n";
        }
        WarmingScheduler.WarmResult r = warmer.warmTopK(50, 60L);
        return "Warmed " + r.stored() + "/" + r.attempted()
                + " keys in " + r.millis() + " ms.\n";
    }

    private static boolean consume(HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        return true;
    }

    private String health() {
        return "OK\n"
                + "cache_entries=" + cache.size() + "\n"
                + "tracked_keys=" + classifier.trackedKeys() + "\n";
    }

    // ------------------------------------------------------------------
    // /stats — human-readable summary
    // ------------------------------------------------------------------

    private String statsPage() {
        CostModel.Report cost = costModel.report(
                stats.totalRequests(), stats.overallHitRatio());

        StringBuilder sb = new StringBuilder(4096);
        sb.append("AdaptCache Pro — Proxy Statistics\n");
        sb.append("=================================\n");
        sb.append("Total requests          : ").append(stats.totalRequests()).append('\n');
        sb.append("Hits                    : ").append(stats.totalHits()).append('\n');
        sb.append("Misses                  : ").append(stats.totalMisses()).append('\n');
        sb.append("Stale serves (SWR/error): ").append(stats.totalStaleServes()).append('\n');
        sb.append("Revalidations (304)     : ").append(stats.totalRevalidations()).append('\n');
        sb.append("Peer hits (cluster)     : ").append(stats.totalPeerHits()).append('\n');
        sb.append("Origin errors           : ").append(stats.totalOriginErrors()).append('\n');
        sb.append("Hit ratio               : ").append(
                String.format(Locale.ROOT, "%.2f%%",
                        stats.overallHitRatio() * 100)).append('\n');
        sb.append("Origin offload          : ").append(
                String.format(Locale.ROOT, "%.2f%%",
                        stats.originOffloadRatio() * 100)).append('\n');
        sb.append("Cache entries           : ").append(cache.size())
                .append('/').append(cache.capacity())
                .append(" (active ").append(cache.activeCapacity())
                .append(")").append('\n');
        sb.append("Eviction / admission    : ").append(cache.evictionPolicyName())
                .append(" / ").append(cache.admissionPolicyName())
                .append('\n');
        sb.append("Tracked keys            : ").append(classifier.trackedKeys())
                .append('\n');
        if (tuner != null) {
            sb.append("Self-tune               : ON, ttlScale=")
                    .append(String.format(Locale.ROOT, "%.2f", tuner.ttlScale()))
                    .append(" swrScale=")
                    .append(String.format(Locale.ROOT, "%.2f", tuner.swrScale()))
                    .append(" adjustments=").append(tuner.adjustments())
                    .append('\n');
        }
        if (energy != null) {
            energy.sample();
            sb.append(String.format(Locale.ROOT,
                    "Energy                  : %.1f J total, %.1f%% CPU, "
                            + "%.4f g CO2%n",
                    energy.totalJoules(), energy.cpuUtilization() * 100,
                    energy.co2Grams()));
            sb.append("Backend reqs avoided    : ")
                    .append(energy.avoidedBackendRequests()).append('\n');
        }
        sb.append('\n');

        sb.append("Requests by class\n");
        sb.append("  HOT      : ").append(stats.requestsByClass(TrafficClass.HOT))
                .append('\n');
        sb.append("  BURSTY   : ").append(stats.requestsByClass(TrafficClass.BURSTY))
                .append('\n');
        sb.append("  ONE_SHOT : ").append(stats.requestsByClass(TrafficClass.ONE_SHOT))
                .append('\n');
        sb.append('\n');

        sb.append("Cost model\n");
        sb.append(String.format(Locale.ROOT,
                "  Exact savings       : $%.6f%n", cost.exactSavingsUsd()));
        sb.append(String.format(Locale.ROOT,
                "  Aggregate (deck eq.): $%.6f%n", cost.aggregateSavingsUsd()));
        sb.append(String.format(Locale.ROOT,
                "  Cache-served bytes  : %d%n", cost.cacheServedBytes()));
        sb.append(String.format(Locale.ROOT,
                "  Savings vs no-cache : %.2f%%%n",
                cost.savingsPercentOfNoCache()));
        sb.append('\n');

        sb.append("Per-key breakdown (top by requests)\n");
        sb.append("----------------------------------\n");
        List<RequestStats> top = stats.snapshot().stream()
                .limit(25)
                .toList();
        for (RequestStats s : top) {
            sb.append(String.format(Locale.ROOT,
                    "  %-40s req=%-5d hit=%-5d miss=%-4d stale=%-4d hr=%5.1f%% avgLat=%.2fms%n",
                    truncate(s.getKey(), 40), s.getRequestCount(),
                    s.getHitCount(), s.getMissCount(), s.getStaleCount(),
                    s.getHitRate() * 100, s.getAverageLatencyMs()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // /metrics — Prometheus text format
    // ------------------------------------------------------------------

    private String metricsPage() {
        StringBuilder sb = new StringBuilder(2048);
        m(sb, "adaptcache_total_requests", stats.totalRequests());
        m(sb, "adaptcache_cache_hits", stats.totalHits());
        m(sb, "adaptcache_cache_misses", stats.totalMisses());
        m(sb, "adaptcache_stale_serves", stats.totalStaleServes());
        m(sb, "adaptcache_revalidations", stats.totalRevalidations());
        m(sb, "adaptcache_peer_hits", stats.totalPeerHits());
        m(sb, "adaptcache_origin_errors", stats.totalOriginErrors());
        m(sb, "adaptcache_origin_round_trips", stats.totalOriginRoundTrips());
        m(sb, "adaptcache_bytes_served_from_cache",
                stats.totalBytesServedFromCache());
        m(sb, "adaptcache_bytes_fetched_from_origin",
                stats.totalBytesFetchedFromOrigin());
        m(sb, "adaptcache_cache_entries", cache.size());
        m(sb, "adaptcache_cache_active_capacity", cache.activeCapacity());
        m(sb, "adaptcache_tracked_keys", classifier.trackedKeys());
        if (tuner != null) {
            g(sb, "adaptcache_tuner_ttl_scale", tuner.ttlScale());
            g(sb, "adaptcache_tuner_swr_scale", tuner.swrScale());
        }
        if (energy != null) {
            energy.sample();
            g(sb, "adaptcache_cpu_utilization", energy.cpuUtilization());
            g(sb, "adaptcache_energy_joules_total", energy.totalJoules());
            g(sb, "adaptcache_co2_grams_estimate", energy.co2Grams());
            m(sb, "adaptcache_backend_requests_avoided",
                    energy.avoidedBackendRequests());
        }
        m(sb, "adaptcache_requests_by_class_hot",
                stats.requestsByClass(TrafficClass.HOT));
        m(sb, "adaptcache_requests_by_class_bursty",
                stats.requestsByClass(TrafficClass.BURSTY));
        m(sb, "adaptcache_requests_by_class_one_shot",
                stats.requestsByClass(TrafficClass.ONE_SHOT));
        g(sb, "adaptcache_hit_ratio", stats.overallHitRatio());
        g(sb, "adaptcache_origin_offload_ratio", stats.originOffloadRatio());
        CostModel.Report cost = costModel.report(
                stats.totalRequests(), stats.overallHitRatio());
        g(sb, "adaptcache_cost_savings_usd", cost.exactSavingsUsd());
        return sb.toString();
    }

    private static void m(StringBuilder sb, String name, long value) {
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void g(StringBuilder sb, String name, double value) {
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ')
                .append(String.format(Locale.ROOT, "%.6f", value))
                .append('\n');
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }
}
