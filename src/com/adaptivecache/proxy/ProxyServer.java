package com.adaptivecache.proxy;

import com.adaptivecache.classifier.TrafficClassifier;
import com.adaptivecache.classifier.TrafficClassifier.ClassificationReport;
import com.adaptivecache.cluster.ConsistentHashRing;
import com.adaptivecache.config.AdaptCacheConfig;
import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.explain.DecisionExplainer;
import com.adaptivecache.green.EnergyMonitor;
import com.adaptivecache.keygen.KeyGenerator;
import com.adaptivecache.keygen.KeyGenerator.CacheKey;
import com.adaptivecache.policy.AdaptivePolicyEngine;
import com.adaptivecache.policy.AdaptivePolicyEngine.CachePolicy;
import com.adaptivecache.policy.AdmissionPolicy;
import com.adaptivecache.policy.AlwaysAdmitPolicy;
import com.adaptivecache.policy.CacsPolicy;
import com.adaptivecache.policy.EvictionPolicy;
import com.adaptivecache.policy.LfuPolicy;
import com.adaptivecache.policy.PriorityAwareLruPolicy;
import com.adaptivecache.policy.TinyLfuAdmissionPolicy;
import com.adaptivecache.predict.EwmaPredictor;
import com.adaptivecache.stats.CostModel;
import com.adaptivecache.stats.RequestStats.Outcome;
import com.adaptivecache.stats.RequestStatsRegistry;
import com.adaptivecache.telemetry.TelemetryEndpoints;
import com.adaptivecache.tuning.SelfTuningController;
import com.adaptivecache.warming.WarmingScheduler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AdaptCache Pro reverse proxy (deck slides 4-6).
 *
 * <p>Per-request pipeline:</p>
 * <ol>
 *   <li>Intercept &amp; generate canonical 64-bit key (Phase 1)</li>
 *   <li>Cache lookup + freshness / SWR / stale-if-error decision (Phase 1)</li>
 *   <li>Statistical profiling: λ + σ² over rolling ring buffers (Phase 2)</li>
 *   <li>Adaptive policy: TTL / SWR / priority mutation (Phase 3)</li>
 *   <li>Feedback: stats, cost model, telemetry (Phase 4)</li>
 * </ol>
 *
 * <p>Concurrency model: one virtual thread per request; all shared state
 * guarded by the cache's {@code ReentrantReadWriteLock}, concurrent maps,
 * and volatile/atomic fields.</p>
 */
public final class ProxyServer {

    private final int port;
    private final String originBaseUrl;
    private final long upstreamTimeoutMs;

    private final LRUCache cache;
    private final TrafficClassifier classifier;
    private final AdaptivePolicyEngine policyEngine;
    private final RequestStatsRegistry stats;
    private final CostModel costModel;

    private final HttpClient upstream;
    private final ExecutorService revalidationExecutor;

    private final EwmaPredictor predictor;
    private final ConsistentHashRing hashRing;
    private final String nodeId;
    private final DecisionExplainer explainer;
    private final EnergyMonitor energy;
    private volatile double ttlScale = 1.0;
    private volatile double swrScale = 1.0;
    private SelfTuningController tuner;
    private WarmingScheduler warmer;

    private HttpServer server;
    private TelemetryEndpoints telemetry;

    public ProxyServer(int port, String originBaseUrl) {
        this(port, originBaseUrl,
                AdaptCacheConfig.CACHE_CAPACITY,
                new AdaptivePolicyEngine(),
                new TrafficClassifier(),
                new RequestStatsRegistry(),
                new CostModel(),
                AdaptCacheConfig.UPSTREAM_TIMEOUT_MS);
    }

    /** Fully injectable constructor (tests pass tiny TTLs / capacities). */
    public ProxyServer(int port, String originBaseUrl, int cacheCapacity,
                       AdaptivePolicyEngine policyEngine,
                       TrafficClassifier classifier,
                       RequestStatsRegistry stats, CostModel costModel,
                       long upstreamTimeoutMs) {
        this.port = port;
        this.originBaseUrl = originBaseUrl;
        this.upstreamTimeoutMs = upstreamTimeoutMs;
        this.cache = new LRUCache(cacheCapacity,
                selectEvictionPolicy(), new AlwaysAdmitPolicy());
        if ("tinylfu".equalsIgnoreCase(
                AdaptCacheConfig.ADMISSION_POLICY)) {
            this.cache.setAdmissionPolicy(new TinyLfuAdmissionPolicy(
                    AdaptCacheConfig.TINYLFU_WIDTH,
                    this.cache::minAccessCountInWindow));
        }
        this.classifier = classifier;
        this.policyEngine = policyEngine;
        this.stats = stats;
        this.costModel = costModel;
        this.upstream = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(upstreamTimeoutMs))
                .build();
        this.revalidationExecutor =
                Executors.newVirtualThreadPerTaskExecutor();
        this.predictor = new EwmaPredictor(
                AdaptCacheConfig.PREDICTOR_ALPHA);
        this.nodeId = AdaptCacheConfig.NODE_ID.isEmpty()
                ? "node-" + port : AdaptCacheConfig.NODE_ID;
        this.hashRing = buildHashRing();
        this.explainer = new DecisionExplainer();
        this.energy = new EnergyMonitor(
                AdaptCacheConfig.NODE_TDP_WATTS,
                AdaptCacheConfig.GRID_CO2_G_PER_KWH);

        // Keep profiling state bounded to what the cache actually holds.
        cache.addEvictionListener(entry -> {
            classifier.remove(entry.getKey());
            predictor.remove(entry.getKey());
        });
    }

    private static EvictionPolicy selectEvictionPolicy() {
        return switch (AdaptCacheConfig.EVICTION_POLICY.toLowerCase(
                Locale.ROOT)) {
            case "lfu" -> new LfuPolicy();
            case "cacs" -> new CacsPolicy();
            default -> new PriorityAwareLruPolicy();
        };
    }

    private ConsistentHashRing buildHashRing() {
        String peers = AdaptCacheConfig.PEER_PROXIES;
        if (peers.isEmpty()) {
            return null;
        }
        java.util.List<String> nodes = new java.util.ArrayList<>();
        nodes.add(nodeId);
        for (String p : peers.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                nodes.add(trimmed);
            }
        }
        if (nodes.size() < 2) {
            return null;
        }
        return new ConsistentHashRing(nodes,
                AdaptCacheConfig.HASH_RING_REPLICAS);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public ProxyServer start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        telemetry = new TelemetryEndpoints(this, stats, costModel,
                classifier, cache);
        telemetry.setPredictor(predictor);
        telemetry.setExplainer(explainer);
        telemetry.setEnergy(energy);
        server.createContext("/", this::handle);
        server.start();
        if (AdaptCacheConfig.SELF_TUNING_ENABLED) {
            tuner = new SelfTuningController(cache, stats,
                    AdaptCacheConfig.TUNER_TARGET_HIT_RATIO,
                    AdaptCacheConfig.TUNER_INTERVAL_SECONDS);
            tuner.start();
        }
        warmer = new WarmingScheduler(cache, predictor, stats,
                originBaseUrl, upstreamTimeoutMs);
        telemetry.setTuner(tuner);
        telemetry.setWarmer(warmer);
        warmer.warmTopK(AdaptCacheConfig.WARM_TOP_K, 60L);
        energy.sample();
        System.out.println("-------------------------------------------");
        System.out.println("Proxy Server Started");
        System.out.println("Listening : http://localhost:" + port());
        System.out.println("Upstream  : " + originBaseUrl);
        System.out.println("Eviction  : " + cache.evictionPolicyName()
                + " / admission " + cache.admissionPolicyName());
        if (hashRing != null) {
            System.out.println("Cluster   : " + nodeId + " + "
                    + (hashRing.nodeCount() - 1) + " peers");
        }
        if (tuner != null) {
            System.out.println("Self-tune : ON (target "
                    + AdaptCacheConfig.TUNER_TARGET_HIT_RATIO + ")");
        }
        System.out.println("Stats     : http://localhost:" + port() + "/stats");
        System.out.println("Metrics   : http://localhost:" + port() + "/metrics");
        System.out.println("-------------------------------------------");
        return this;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (tuner != null) {
            tuner.stop();
        }
        revalidationExecutor.shutdownNow();
    }

    public int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    public LRUCache cache() {
        return cache;
    }

    public RequestStatsRegistry stats() {
        return stats;
    }

    public CostModel costModel() {
        return costModel;
    }

    public TrafficClassifier classifier() {
        return classifier;
    }

    public EwmaPredictor predictor() {
        return predictor;
    }

    public DecisionExplainer explainer() {
        return explainer;
    }

    public EnergyMonitor energy() {
        return energy;
    }

    public SelfTuningController tuner() {
        return tuner;
    }

    public WarmingScheduler warmer() {
        return warmer;
    }

    public ConsistentHashRing hashRing() {
        return hashRing;
    }

    public String nodeId() {
        return nodeId;
    }

    public double ttlScale() {
        return tuner != null ? tuner.ttlScale() : ttlScale;
    }

    public void setTtlScale(double scale) {
        this.ttlScale = Math.max(0.25, Math.min(4.0, scale));
    }

    public double swrScale() {
        return tuner != null ? tuner.swrScale() : swrScale;
    }

    public void setSwrScale(double scale) {
        this.swrScale = Math.max(0.25, Math.min(4.0, scale));
    }

    CachePolicy scaledPolicy(CachePolicy base) {
        double ts = ttlScale();
        double ss = swrScale();
        if (ts == 1.0 && ss == 1.0) {
            return base;
        }
        return new CachePolicy(base.trafficClass(),
                Math.max(1, Math.round(base.ttlSeconds() * ts)),
                Math.max(0, Math.round(base.swrSeconds() * ss)),
                base.backgroundRefresh(), base.priority());
    }

    // ------------------------------------------------------------------
    // Request pipeline
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) {
        try {
            // Telemetry / internal endpoints bypass the cache pipeline.
            if (telemetry.handle(exchange)) {
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String requestUri = exchange.getRequestURI().toString();
            long startNanos = System.nanoTime();

            CacheKey key = KeyGenerator.generate(method, requestUri);

            // Non-cacheable methods pass through without storing.
            if (!(method.equals("GET") || method.equals("HEAD"))) {
                passThrough(exchange, method, requestUri);
                return;
            }

            long now = System.currentTimeMillis();
            CacheEntry entry = cache.get(key.canonical());

            ClassificationReport report = classifier.observe(key.canonical(), now);
            predictor.observe(key.canonical(), now);
            CachePolicy policy = scaledPolicy(policyEngine.policyFor(report));
            if (entry != null) {
                entry.setPredictedScore(predictor.predict(
                        key.canonical(), 60L, 100.0));
            }

            if (entry != null
                    && entry.getTrafficClass() != policy.trafficClass()) {
                // TTL re-anchors only on class transitions; plain hits must
                // not extend expiry or SWR/stale-if-error would never fire.
                entry.applyPolicy(policy.ttlMillis(), policy.swrMillis(),
                        policyEngine.staleIfErrorSeconds() * 1000L,
                        policy.trafficClass(), now);
            }

            // ---- Fresh hit ----
            if (entry != null && now < entry.getExpiresAt()) {
                String inm = exchange.getRequestHeaders()
                        .getFirst("If-None-Match");
                if (inm != null && entry.getEtag() != null
                        && inm.equals(entry.getEtag())) {
                    sendNotModified(exchange, entry, report, policy);
                    record(exchange, key, report, Outcome.REVALIDATED,
                            startNanos, entry.getBody().length, true);
                    return;
                }
                serveFromCache(exchange, entry, report, policy, "HIT", now);
                record(exchange, key, report, Outcome.HIT,
                        startNanos, entry.getBody().length, true);
                return;
            }

            // ---- RFC 5861 stale-while-revalidate ----
            if (entry != null
                    && now < entry.getExpiresAt() + entry.getSwrWindowMs()
                    && entry.getSwrWindowMs() > 0) {
                serveFromCache(exchange, entry, report, policy, "STALE", now);
                record(exchange, key, report, Outcome.STALE,
                        startNanos, entry.getBody().length, true);
                triggerRevalidation(key.canonical(), entry, policy);
                return;
            }

            // ---- Miss: peer shard first, then origin ----
            if (hashRing != null && entry == null) {
                String owner = hashRing.ownerOf(key.canonical());
                if (owner != null && !owner.equals(nodeId)
                        && forwardToPeer(exchange, key, owner, report,
                        policy, method, requestUri, startNanos)) {
                    return;
                }
            }
            forwardToOrigin(exchange, key, entry, report, policy, method,
                    requestUri, startNanos);

        } catch (Exception e) {
            handleInternalError(exchange, e);
        }
    }

    // ------------------------------------------------------------------
    // Serving paths
    // ------------------------------------------------------------------

    private void serveFromCache(HttpExchange exchange, CacheEntry entry,
                                ClassificationReport report, CachePolicy policy,
                                String cacheState, long now) throws IOException {
        byte[] body = entry.getBody();
        long ageSeconds = Math.max(0, (now - entry.getStoredAt()) / 1000);

        exchange.getResponseHeaders().set(
                "Content-Type", contentType(entry));
        exchange.getResponseHeaders().set("ETag", entry.getEtag());
        exchange.getResponseHeaders().set(
                "Cache-Control", cacheControlHeader(policy, entry, now));
        exchange.getResponseHeaders().set("Age", Long.toString(ageSeconds));
        exchange.getResponseHeaders().set("X-Cache", cacheState);
        exchange.getResponseHeaders().set(
                "X-Traffic-Class", report.trafficClass().name());
        exchange.getResponseHeaders().set(
                "X-Cache-Key", entry.getKey());

        if ("STALE".equals(cacheState)) {
            exchange.getResponseHeaders().add(
                    "Warning", "110 - \"Response is Stale\"");
        }

        sendBody(exchange, 200, body);
        entry.bumpAccessCount();
        costModel.recordCacheServe(body.length);
    }

    private void sendNotModified(HttpExchange exchange, CacheEntry entry,
                                 ClassificationReport report,
                                 CachePolicy policy) throws IOException {
        exchange.getResponseHeaders().set("ETag", entry.getEtag());
        exchange.getResponseHeaders().set(
                "Cache-Control", cacheControlHeader(policy, entry,
                System.currentTimeMillis()));
        exchange.getResponseHeaders().set("X-Cache", "REVALIDATED");
        exchange.getResponseHeaders().set(
                "X-Traffic-Class", report.trafficClass().name());
        exchange.sendResponseHeaders(304, -1);
        exchange.close();
        entry.bumpAccessCount();
        costModel.recordCacheServe(0);
    }

    private boolean forwardToPeer(HttpExchange exchange, CacheKey key,
                                    String peerBase, ClassificationReport report,
                                    CachePolicy policy, String method,
                                    String requestUri, long startNanos) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(peerBase + requestUri))
                    .timeout(Duration.ofMillis(upstreamTimeoutMs));
            copyHeader(exchange, builder, "Accept");
            if ("HEAD".equals(method)) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else {
                builder.GET();
            }
            HttpResponse<byte[]> peerResponse = upstream.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = peerResponse.statusCode();
            if (status < 200 || status >= 300) {
                return false;
            }
            byte[] body = peerResponse.body();
            String contentType = peerResponse.headers()
                    .firstValue("Content-Type")
                    .orElse("application/octet-stream");
            exchange.getResponseHeaders().set("Content-Type", contentType);
            peerResponse.headers().firstValue("ETag").ifPresent(
                    v -> exchange.getResponseHeaders().set("ETag", v));
            exchange.getResponseHeaders().set("X-Cache", "PEER-HIT");
            exchange.getResponseHeaders().set(
                    "X-Peer", peerBase);
            exchange.getResponseHeaders().set(
                    "X-Traffic-Class", report.trafficClass().name());
            sendBody(exchange, 200, body);
            energy.recordAvoidedBackendRequest();
            record(exchange, key, report, Outcome.PEER_HIT,
                    startNanos, body.length, true);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private void forwardToOrigin(HttpExchange exchange, CacheKey key,
                                 CacheEntry staleEntry,
                                 ClassificationReport report,
                                 CachePolicy policy, String method,
                                 String requestUri, long startNanos)
            throws IOException {

        long now = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(originBaseUrl + requestUri))
                    .timeout(Duration.ofMillis(upstreamTimeoutMs));

            if (staleEntry != null && staleEntry.getEtag() != null) {
                builder.header("If-None-Match", staleEntry.getEtag());
            }
            copyHeader(exchange, builder, "Accept");
            copyHeader(exchange, builder, "User-Agent");

            switch (method) {
                case "HEAD" -> builder.method("HEAD",
                        HttpRequest.BodyPublishers.noBody());
                case "GET" -> builder.GET();
                default -> builder.method(method, HttpRequest.BodyPublishers
                        .ofByteArray(exchange.getRequestBody().readAllBytes()));
            }

            HttpResponse<byte[]> upstreamResponse =
                    upstream.send(builder.build(),
                            HttpResponse.BodyHandlers.ofByteArray());

            int status = upstreamResponse.statusCode();
            byte[] body = upstreamResponse.body();
            String etag = upstreamResponse.headers()
                    .firstValue("ETag").orElse(null);
            String contentType = upstreamResponse.headers()
                    .firstValue("Content-Type")
                    .orElse("application/octet-stream");

            if (status == 304 && staleEntry != null) {
                // Conditional revalidation: refresh without body transfer.
                staleEntry.setRevalidatedAt(now);
                staleEntry.setStoredAt(now);
                staleEntry.applyPolicy(policy.ttlMillis(),
                        policy.swrMillis(),
                        policyEngine.staleIfErrorSeconds() * 1000L,
                        policy.trafficClass(), now);
                serveFromCache(exchange, staleEntry, report, policy,
                        "REVALIDATED", now);
                record(exchange, key, report, Outcome.REVALIDATED,
                        startNanos, staleEntry.getBody().length, true);
                return;
            }

            if (status >= 200 && status < 300) {
                long backendMs = (System.nanoTime() - startNanos) / 1_000_000;
                CacheEntry fresh = new CacheEntry(key.canonical(),
                        key.hash(), body);
                fresh.setEtag(etag);
                fresh.setContentType(contentType);
                fresh.setBackendLatencyMs(backendMs);
                fresh.setPredictedScore(predictor.predict(
                        key.canonical(), 60L, 100.0));
                fresh.applyPolicy(policy.ttlMillis(), policy.swrMillis(),
                        policyEngine.staleIfErrorSeconds() * 1000L,
                        policy.trafficClass(), now);
                boolean admitted = cache.put(key.canonical(), fresh);

                serveUpstreamBody(exchange, fresh, report, policy,
                        admitted ? "MISS" : "MISS-NOT-CACHED",
                        status, contentType, now, body);
                record(exchange, key, report, Outcome.MISS,
                        startNanos, body.length, false);
                return;
            }

            // Client errors (4xx) pass through verbatim — never cached,
            // never rewritten into a proxy error.
            if (status >= 400 && status < 500) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("X-Cache", "BYPASS");
                sendBody(exchange, status, body);
                record(exchange, key, report, Outcome.MISS,
                        startNanos, body.length, false);
                return;
            }

            // Origin returned an error status → stale-if-error fallback.
            stats.recordOriginError();
            if (staleEntry != null) {
                serveStaleOnError(exchange, staleEntry, report, policy, now);
                record(exchange, key, report, Outcome.STALE,
                        startNanos, staleEntry.getBody().length, true);
                return;
            }
            sendError(exchange, 503, "Origin error (" + status + ")");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.recordOriginError();
            if (staleEntry != null) {
                serveStaleOnError(exchange, staleEntry, report, policy,
                        System.currentTimeMillis());
                record(exchange, key, report, Outcome.STALE,
                        startNanos, staleEntry.getBody().length, true);
            } else {
                sendError(exchange, 503, "Origin unreachable (interrupted)");
            }
        } catch (IOException e) {
            // Timeout / connection failure → stale-if-error fallback.
            stats.recordOriginError();
            if (staleEntry != null) {
                serveStaleOnError(exchange, staleEntry, report, policy,
                        System.currentTimeMillis());
                record(exchange, key, report, Outcome.STALE,
                        startNanos, staleEntry.getBody().length, true);
            } else {
                sendError(exchange, 503, "Origin unreachable");
            }
        }
    }

    private void serveUpstreamBody(HttpExchange exchange, CacheEntry entry,
                                   ClassificationReport report,
                                   CachePolicy policy, String cacheState,
                                   int upstreamStatus, String contentType,
                                   long now, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (entry.getEtag() != null) {
            exchange.getResponseHeaders().set("ETag", entry.getEtag());
        }
        exchange.getResponseHeaders().set(
                "Cache-Control", cacheControlHeader(policy, entry, now));
        exchange.getResponseHeaders().set("Age", "0");
        exchange.getResponseHeaders().set("X-Cache", cacheState);
        exchange.getResponseHeaders().set(
                "X-Traffic-Class", report.trafficClass().name());
        exchange.getResponseHeaders().set(
                "X-Cache-Key", entry.getKey());
        sendBody(exchange, upstreamStatus, body);
    }

    private void serveStaleOnError(HttpExchange exchange, CacheEntry entry,
                                   ClassificationReport report,
                                   CachePolicy policy, long now)
            throws IOException {
        byte[] body = entry.getBody();
        long ageSeconds = Math.max(0, (now - entry.getStoredAt()) / 1000);
        exchange.getResponseHeaders().set(
                "Content-Type", contentType(entry));
        if (entry.getEtag() != null) {
            exchange.getResponseHeaders().set("ETag", entry.getEtag());
        }
        exchange.getResponseHeaders().set(
                "Cache-Control", cacheControlHeader(policy, entry, now));
        exchange.getResponseHeaders().set("Age", Long.toString(ageSeconds));
        exchange.getResponseHeaders().add(
                "Warning", "111 - \"Revalidation failed; serving stale\"");
        exchange.getResponseHeaders().set("X-Cache", "STALE");
        exchange.getResponseHeaders().set(
                "X-Traffic-Class", report.trafficClass().name());
        exchange.getResponseHeaders().set("X-Cache-Key", entry.getKey());
        sendBody(exchange, 200, body);
        entry.bumpAccessCount();
        costModel.recordCacheServe(body.length);
    }

    /** Non-cacheable methods (POST/PUT/DELETE) pass through untouched. */
    private void passThrough(HttpExchange exchange, String method,
                             String requestUri) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(originBaseUrl + requestUri))
                    .timeout(Duration.ofMillis(upstreamTimeoutMs));
            copyHeader(exchange, builder, "Content-Type");
            copyHeader(exchange, builder, "Accept");

            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            builder.method(method,
                    reqBody.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(reqBody));

            HttpResponse<byte[]> upstreamResponse =
                    upstream.send(builder.build(),
                            HttpResponse.BodyHandlers.ofByteArray());

            byte[] body = upstreamResponse.body();
            String contentType = upstreamResponse.headers()
                    .firstValue("Content-Type").orElse(null);
            if (contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            exchange.getResponseHeaders().set("X-Cache", "BYPASS");
            sendBody(exchange, upstreamResponse.statusCode(), body);
        } catch (Exception e) {
            try {
                sendError(exchange, 502, "Upstream failure");
            } catch (IOException ignored) {
                exchange.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Background RFC 5861 revalidation
    // ------------------------------------------------------------------

    private void triggerRevalidation(String key, CacheEntry entry,
                                     CachePolicy policy) {
        synchronized (entry) {
            if (entry.isRevalidating()) {
                return;
            }
            entry.setRevalidating(true);
        }
        CompletableFuture.runAsync(
                () -> doRevalidation(key, entry, policy),
                revalidationExecutor);
    }

    private void doRevalidation(String key, CacheEntry entry,
                                CachePolicy policy) {
        long now = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(originBaseUrl + entry.getKey()))
                    .timeout(Duration.ofMillis(upstreamTimeoutMs))
                    .GET();
            if (entry.getEtag() != null) {
                builder.header("If-None-Match", entry.getEtag());
            }

            HttpResponse<byte[]> upstreamResponse =
                    upstream.send(builder.build(),
                            HttpResponse.BodyHandlers.ofByteArray());

            int status = upstreamResponse.statusCode();
            if (status == 200) {
                byte[] body = upstreamResponse.body();
                entry.setBody(body);
                entry.setEtag(upstreamResponse.headers()
                        .firstValue("ETag").orElse(entry.getEtag()));
                entry.setStoredAt(now);
                entry.setRevalidatedAt(now);
            } else if (status == 304) {
                entry.setStoredAt(now);
                entry.setRevalidatedAt(now);
            } else {
                stats.recordOriginError();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.recordOriginError();
        } catch (IOException e) {
            stats.recordOriginError();
        } finally {
            entry.setRevalidating(false);
            entry.applyPolicy(policy.ttlMillis(), policy.swrMillis(),
                    policyEngine.staleIfErrorSeconds() * 1000L,
                    policy.trafficClass(), System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------
    // Bookkeeping helpers
    // ------------------------------------------------------------------

    private void record(HttpExchange exchange, CacheKey key,
                        ClassificationReport report, Outcome outcome,
                        long startNanos, long size, boolean servedFromCache) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        stats.record(key.canonical(), outcome, report.trafficClass(),
                latencyMs, size, servedFromCache);
    }

    private static void copyHeader(HttpExchange exchange,
                                   HttpRequest.Builder builder, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value != null) {
            builder.header(name, value);
        }
    }

    private static String contentType(CacheEntry entry) {
        return entry.getContentType() == null
                ? "application/octet-stream"
                : entry.getContentType();
    }

    private static String cacheControlHeader(CachePolicy policy,
                                             CacheEntry entry, long now) {
        long remainingSeconds = Math.max(0,
                (entry.getExpiresAt() - now + 999) / 1000);
        StringBuilder sb = new StringBuilder()
                .append("public, max-age=").append(remainingSeconds);
        if (policy.swrSeconds() > 0) {
            sb.append(", stale-while-revalidate=")
                    .append(policy.swrSeconds());
        }
        if (entry.getStaleIfErrorMs() > 0) {
            sb.append(", stale-if-error=")
                    .append(entry.getStaleIfErrorMs() / 1000);
        }
        return sb.toString();
    }

    private static void sendBody(HttpExchange exchange, int status,
                                 byte[] body) throws IOException {
        boolean head = "HEAD".equalsIgnoreCase(
                exchange.getRequestMethod());
        exchange.sendResponseHeaders(status,
                head ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } else {
            exchange.close();
        }
    }

    private static void sendError(HttpExchange exchange, int status,
                                  String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message
                + "\",\"status\":" + status + "}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Retry-After", "1");
        exchange.getResponseHeaders().set("X-Cache", "ERROR");
        sendBody(exchange, status, body);
    }

    private void handleInternalError(HttpExchange exchange, Exception e) {
        try {
            e.printStackTrace();
            sendError(exchange, 500, "Internal proxy error");
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    /** Convenience for tests / logs. */
    public List<String> telemetryPaths() {
        return List.of("/stats", "/metrics", "/_health");
    }
}
