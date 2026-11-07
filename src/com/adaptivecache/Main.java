package com.adaptivecache;

import com.adaptivecache.config.AdaptCacheConfig;
import com.adaptivecache.eval.EvaluationHarness;
import com.adaptivecache.origin.OriginServer;
import com.adaptivecache.proxy.ProxyServer;
import com.adaptivecache.workload.WorkloadGenerator;

import java.util.Locale;

/**
 * AdaptCache Pro entry point.
 *
 * <p>Modes:</p>
 * <ul>
 *   <li>{@code server} (default) — starts origin + proxy together</li>
 *   <li>{@code origin} — origin server only</li>
 *   <li>{@code proxy} — proxy only (requires a running origin)</li>
 *   <li>{@code workload} — runs the Zipfian/Poisson client against a proxy</li>
 *   <li>{@code eval} — offline head-to-head of eviction/admission policies</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0
                ? args[0].toLowerCase(Locale.ROOT) : "server";

        switch (mode) {
            case "server" -> {
                OriginServer origin = new OriginServer(
                        AdaptCacheConfig.ORIGIN_PORT);
                origin.start();
                ProxyServer proxy = new ProxyServer(
                        AdaptCacheConfig.PROXY_PORT,
                        AdaptCacheConfig.ORIGIN_BASE_URL);
                proxy.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutting down...");
                    proxy.stop();
                    origin.stop();
                }));
                Thread.currentThread().join();
            }
            case "origin" -> new OriginServer(
                    AdaptCacheConfig.ORIGIN_PORT).start();
            case "proxy" -> {
                ProxyServer proxy = new ProxyServer(
                        AdaptCacheConfig.PROXY_PORT,
                        AdaptCacheConfig.ORIGIN_BASE_URL);
                proxy.start();
                Thread.currentThread().join();
            }
            case "workload" -> {
                int total = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
                double rate = args.length > 2 ? Double.parseDouble(args[2]) : 50;
                int concurrency = args.length > 3
                        ? Integer.parseInt(args[3]) : 32;
                WorkloadGenerator generator = new WorkloadGenerator(
                        "http://localhost:" + AdaptCacheConfig.PROXY_PORT,
                        total, rate, concurrency, 42);
                generator.run();
            }
            case "eval" -> {
                int capacity = args.length > 1
                        ? Integer.parseInt(args[1]) : 64;
                int total = args.length > 2
                        ? Integer.parseInt(args[2]) : 20000;
                int keys = args.length > 3
                        ? Integer.parseInt(args[3]) : 500;
                EvaluationHarness.main(new String[]{
                        String.valueOf(capacity), String.valueOf(total),
                        String.valueOf(keys)});
            }
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("""
                Usage: java -cp out com.adaptivecache.Main <mode> [args]

                Modes:
                  server            start origin + proxy (default)
                  origin            origin server only
                  proxy             proxy only
                  workload [N] [λ] [C]   run synthetic workload
                                         N = total requests (default 1000)
                                         λ = Poisson rate req/s (default 50)
                                         C = client concurrency (default 32)
                  eval [cap] [N] [K]     offline policy comparison
                                         cap = cache capacity (default 64)
                                         N = trace requests (default 20000)
                                         K = key space (default 500)
                """);
    }
}
