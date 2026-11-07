package com.adaptivecache.eval;

import com.adaptivecache.classifier.TrafficClass;
import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.policy.AdmissionPolicy;
import com.adaptivecache.policy.AlwaysAdmitPolicy;
import com.adaptivecache.policy.CacsPolicy;
import com.adaptivecache.policy.EvictionPolicy;
import com.adaptivecache.policy.LfuPolicy;
import com.adaptivecache.policy.PriorityAwareLruPolicy;
import com.adaptivecache.policy.TinyLfuAdmissionPolicy;
import com.adaptivecache.predict.EwmaPredictor;
import com.adaptivecache.workload.ZipfianGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Rigorous experimental evaluation (Item 10). Replays an identical seeded
 * Zipfian trace against four cache configurations and reports hit ratio,
 * virtual latency (avg/p50/p95), backend requests, bytes and evictions.
 *
 * <p>The comparison is deterministic (fixed seed + virtual clock) and
 * offline: it isolates eviction/admission quality from network noise.
 * TTL/expiry behaviour is proxy-level and covered by the integration
 * tests, not by this harness.</p>
 */
public final class EvaluationHarness {

    public record Config(String name, EvictionPolicy eviction,
                         AdmissionPolicy admission) {
    }

    public record Result(String name, int requests, long hits, long misses,
                         long admitted, long rejected, long evictions,
                         double hitRatio, double avgLatencyMs,
                         double p50LatencyMs, double p95LatencyMs,
                         long backendRequests, long bytesServed) {
    }

    private final int capacity;
    private final int totalRequests;
    private final long seed;
    private final int keySpace;

    public EvaluationHarness(int capacity, int totalRequests,
                             int keySpace, long seed) {
        this.capacity = capacity;
        this.totalRequests = totalRequests;
        this.keySpace = keySpace;
        this.seed = seed;
    }

    public List<Result> run() {
        List<Result> results = new ArrayList<>();
        results.add(runOne("priority-lru", new PriorityAwareLruPolicy(),
                new AlwaysAdmitPolicy()));
        results.add(runOne("lfu", new LfuPolicy(), new AlwaysAdmitPolicy()));
        results.add(runOne("cacs", new CacsPolicy(), new AlwaysAdmitPolicy()));
        results.add(runTinyLfu());
        results.sort(Comparator.comparingDouble(Result::hitRatio).reversed());
        return results;
    }

    private Result runTinyLfu() {
        LRUCache cache = new LRUCache(capacity,
                new PriorityAwareLruPolicy(), new AlwaysAdmitPolicy());
        TinyLfuAdmissionPolicy tiny = new TinyLfuAdmissionPolicy(4096,
                cache::minAccessCountInWindow);
        cache.setAdmissionPolicy(tiny);
        return simulate("tinylfu+lru", cache, tiny);
    }

    private Result runOne(String name, EvictionPolicy eviction,
                          AdmissionPolicy admission) {
        LRUCache cache = new LRUCache(capacity, eviction, admission);
        return simulate(name, cache,
                admission instanceof TinyLfuAdmissionPolicy t ? t : null);
    }

    private Result simulate(String name, LRUCache cache,
                            TinyLfuAdmissionPolicy tiny) {
        ZipfianGenerator zipf = new ZipfianGenerator(keySpace, 1.0, seed);
        EwmaPredictor predictor = new EwmaPredictor(0.3);
        long clock = 1_000_000L;

        long hits = 0;
        long misses = 0;
        long admitted = 0;
        long rejected = 0;
        long backendRequests = 0;
        long bytesServed = 0;
        List<Double> latencies = new ArrayList<>(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            clock += 5;
            int rank = zipf.nextRank();
            String path = zipf.pathForRank(rank, 0.3, 0.2);
            String key = "GET " + path;
            if (tiny != null) {
                tiny.recordAccess(key);
            }
            predictor.observe(key, clock);

            CacheEntry entry = cache.get(key);
            if (entry != null) {
                entry.bumpAccessCount();
                hits++;
                bytesServed += entry.getBody().length;
                latencies.add(1.0);
                continue;
            }
            misses++;
            backendRequests++;
            double backendMs = backendModel(path);
            int size = sizeModel(path, rank);
            byte[] body = new byte[size];
            Arrays.fill(body, (byte) ('a' + (rank % 26)));
            CacheEntry fresh = new CacheEntry(key, key.hashCode(), body);
            fresh.setBackendLatencyMs((long) backendMs);
            fresh.setPredictedScore(predictor.predict(key, 60L, 100.0));
            fresh.setTrafficClass(classOf(path));
            long now = System.currentTimeMillis();
            fresh.setStoredAt(now);
            fresh.applyPolicy(300_000L, 0L, 3_600_000L,
                    classOf(path), now);
            if (cache.put(key, fresh)) {
                admitted++;
            } else {
                rejected++;
            }
            latencies.add(backendMs);
        }
        long evictions = admitted - cache.size();
        double[] sorted = latencies.stream().mapToDouble(d -> d)
                .sorted().toArray();
        double avg = Arrays.stream(sorted).average().orElse(0.0);
        return new Result(name, totalRequests, hits, misses, admitted,
                rejected, Math.max(0, evictions),
                (double) hits / totalRequests, avg,
                percentile(sorted, 50), percentile(sorted, 95),
                backendRequests, bytesServed);
    }

    private static double backendModel(String path) {
        if (path.startsWith("/hot/")) {
            return 20.0;
        }
        if (path.startsWith("/bursty/")) {
            return 120.0;
        }
        return 250.0;
    }

    private static int sizeModel(String path, int rank) {
        if (path.startsWith("/hot/")) {
            return 256;
        }
        if (path.startsWith("/bursty/")) {
            return 1024;
        }
        return 64 + (rank % 8) * 64;
    }

    private static TrafficClass classOf(String path) {
        if (path.startsWith("/hot/")) {
            return TrafficClass.HOT;
        }
        if (path.startsWith("/bursty/")) {
            return TrafficClass.BURSTY;
        }
        return TrafficClass.ONE_SHOT;
    }

    private static double percentile(double[] sorted, int p) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    public String markdownTable(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AdaptCache Pro — Policy Evaluation\n\n");
        sb.append(String.format(Locale.ROOT,
                "Trace: Zipfian(s=1.0, keys=%d), %d requests, capacity %d, "
                        + "seed %d, virtual clock.\n\n",
                keySpace, totalRequests, capacity, seed));
        sb.append("| policy | hit ratio | avg lat (ms) | p50 (ms) | p95 (ms) "
                + "| backend reqs | admitted | rejected | evictions | MB served |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            sb.append(String.format(Locale.ROOT,
                    "| %s | %.2f%% | %.2f | %.2f | %.2f | %d | %d | %d | %d "
                            + "| %.2f |\n",
                    r.name(), r.hitRatio() * 100, r.avgLatencyMs(),
                    r.p50LatencyMs(), r.p95LatencyMs(), r.backendRequests(),
                    r.admitted(), r.rejected(), r.evictions(),
                    r.bytesServed() / 1048576.0));
        }
        Result best = results.get(0);
        sb.append(String.format(Locale.ROOT,
                "\nBest hit ratio: **%s (%.2f%%)**.\n", best.name(),
                best.hitRatio() * 100));
        return sb.toString();
    }

    public void writeReport(Path out, List<Result> results)
            throws IOException {
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdownTable(results),
                StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        int capacity = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        int total = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
        int keys = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        EvaluationHarness harness = new EvaluationHarness(
                capacity, total, keys, 42L);
        List<Result> results = harness.run();
        System.out.println(harness.markdownTable(results));
        harness.writeReport(
                Path.of("docs/eval-results.md"), results);
        System.out.println("Report written to docs/eval-results.md");
    }
}
