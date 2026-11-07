package com.adaptivecache.workload;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthetic workload generator (deck slide 9 — benchmarking module):
 * fires Zipfian-popularity requests with Poisson-distributed inter-arrival
 * gaps at the proxy, then prints a client-side summary.
 */
public final class WorkloadGenerator {

    private final String proxyBaseUrl;
    private final int totalRequests;
    private final double poissonRatePerSec;   // λ of arrivals
    private final int concurrency;
    private final ZipfianGenerator zipf;

    public WorkloadGenerator(String proxyBaseUrl, int totalRequests,
                             double poissonRatePerSec, int concurrency,
                             long seed) {
        this.proxyBaseUrl = proxyBaseUrl;
        this.totalRequests = totalRequests;
        this.poissonRatePerSec = poissonRatePerSec;
        this.concurrency = Math.max(1, concurrency);
        this.zipf = new ZipfianGenerator(100, 1.0, seed);
    }

    /** Client-side aggregate counters. */
    public record Summary(int total, int hits, int misses, int stale,
                          int errors, double meanLatencyMs,
                          long durationMs, double requestsPerSec,
                          double hitRatioPercent) {
    }

    public Summary run() throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        AtomicInteger hits = new AtomicInteger();
        AtomicInteger misses = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong totalLatencyNanos = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();

        Random gapRandom = new Random(1234);

        long start = System.nanoTime();

        // Poisson arrivals: sequential dispatcher feeding worker threads.
        Thread dispatcher = new Thread(() -> {
            long nextFireNs = System.nanoTime();
            for (int i = 0; i < totalRequests; i++) {
                long nowNs = System.nanoTime();
                long delayNs = nextFireNs - nowNs;
                if (delayNs > 0) {
                    try {
                        Thread.sleep(delayNs / 1_000_000,
                                (int) (delayNs % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                nextFireNs = nextFireNs + (long) (poissonGapMs(gapRandom,
                        poissonRatePerSec) * 1_000_000);

                int rank = zipf.nextRank();
                String path = zipf.pathForRank(rank, 0.3, 0.2);

                pool.submit(() -> {
                    long t0 = System.nanoTime();
                    try {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(proxyBaseUrl + path))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build();
                        HttpResponse<String> resp = client.send(req,
                                HttpResponse.BodyHandlers.ofString());
                        String xCache = resp.headers()
                                .firstValue("X-Cache").orElse("?");
                        switch (xCache) {
                            case "HIT", "REVALIDATED" ->
                                    hits.incrementAndGet();
                            case "MISS" -> misses.incrementAndGet();
                            case "STALE" -> stale.incrementAndGet();
                            default -> misses.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        totalLatencyNanos.addAndGet(System.nanoTime() - t0);
                        completed.incrementAndGet();
                    }
                });
            }
        });
        dispatcher.start();
        dispatcher.join();

        // Drain in-flight requests.
        pool.shutdown();
        while (!pool.isTerminated()) {
            Thread.sleep(10);
        }

        long durationNanos = System.nanoTime() - start;
        double durationMs = durationNanos / 1e6;
        int done = completed.get();
        double meanLatency = done == 0 ? 0
                : totalLatencyNanos.get() / 1e6 / done;
        double rps = durationMs == 0 ? 0 : done / (durationMs / 1000.0);
        double hitRatio = done == 0 ? 0
                : (double) hits.get() / done * 100.0;

        Summary summary = new Summary(done, hits.get(), misses.get(),
                stale.get(), errors.get(), meanLatency,
                (long) durationMs, rps, hitRatio);
        print(summary);
        return summary;
    }

    /** Sample from an exponential distribution → Poisson gap in ms. */
    private static double poissonGapMs(Random random, double ratePerSec) {
        if (ratePerSec <= 0) {
            return 0;
        }
        return -Math.log(1.0 - random.nextDouble()) * 1000.0 / ratePerSec;
    }

    private static void print(Summary s) {
        System.out.println();
        System.out.println("========== WORKLOAD SUMMARY ==========");
        System.out.printf(Locale.ROOT, "Requests      : %d%n", s.total());
        System.out.printf(Locale.ROOT, "Hits          : %d%n", s.hits());
        System.out.printf(Locale.ROOT, "Misses        : %d%n", s.misses());
        System.out.printf(Locale.ROOT, "Stale serves  : %d%n", s.stale());
        System.out.printf(Locale.ROOT, "Errors        : %d%n", s.errors());
        System.out.printf(Locale.ROOT, "Hit ratio     : %.2f%%%n", s.hitRatioPercent());
        System.out.printf(Locale.ROOT, "Mean latency  : %.2f ms%n", s.meanLatencyMs());
        System.out.printf(Locale.ROOT, "Duration      : %d ms%n", s.durationMs());
        System.out.printf(Locale.ROOT, "Throughput    : %.1f req/s%n", s.requestsPerSec());
        System.out.println("======================================");
    }
}
