package com.adaptivecache;

import com.adaptivecache.classifier.TrafficClassifier;
import com.adaptivecache.core.CacheEntry;
import com.adaptivecache.core.LRUCache;
import com.adaptivecache.keygen.KeyGenerator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks for the hot paths of AdaptCache Pro
 * (deck slide 9 — benchmarking module).
 *
 * <p>Run via {@code scripts/bench.sh}.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CacheBenchmark {

    private LRUCache cache;
    private TrafficClassifier classifier;
    private byte[] payload;
    private long ts;

    @Setup
    public void setup() {
        cache = new LRUCache(1024);
        classifier = new TrafficClassifier();
        payload = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 256; i++) {
            String key = "/hot/item-" + i;
            CacheEntry e = new CacheEntry(key, KeyGenerator
                    .generate("GET", key).hash(), payload);
            cache.put(key, e);
        }
        ts = System.currentTimeMillis();
    }

    @Benchmark
    public CacheEntry cacheHit() {
        return cache.get("/hot/item-7");
    }

    @Benchmark
    public CacheEntry cacheMiss() {
        return cache.get("/hot/absent-key");
    }

    @Benchmark
    public void cachePutEvicting() {
        cache.put("/hot/rotating-" + (ts++ & 0x3ff), entry());
    }

    @Benchmark
    public long keyGeneration() {
        return KeyGenerator.generate("GET",
                "/api/data?a=1&b=2&c=3&d=4").hash();
    }

    @Benchmark
    public void classifierObserve() {
        classifier.observe("/hot/classify-me");
    }

    private CacheEntry entry() {
        CacheEntry e = new CacheEntry("/hot/rotating",
                KeyGenerator.generate("GET", "/hot/rotating").hash(),
                payload);
        return e;
    }
}
