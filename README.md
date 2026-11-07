# AdaptCache Pro

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/aryangup451-del/AdaptCachePro)
[![Build and Publish](https://github.com/aryangup451-del/AdaptCachePro/actions/workflows/deploy.yml/badge.svg)](https://github.com/aryangup451-del/AdaptCachePro/actions/workflows/deploy.yml)

**An Intelligent Adaptive HTTP Cache &amp; Reverse Proxy for High-Concurrency
Cloud Systems**

AdaptCache Pro is a zero-dependency **Java 21** reverse proxy that classifies
live traffic into three workload profiles — **Hot**, **Bursty**, and
**One-Shot** — using real-time statistical profiling (frequency λ and
inter-arrival variance σ² over bounded rolling ring buffers), then applies
differentiated TTLs, RFC 5861 *Stale-While-Revalidate*, RFC 7232 conditional
revalidation, and a quantifiable cloud-cost model.

> EDI-1 MSE Review — Group 04 (Div CS-B), Vishwakarma Institute of Technology,
> Pune. See `PLAN.md` for the full RALPLAN-DR design document and
> `AdaptCache_Pro_MSE_Review.pptx` for the project deck.

---

## Table of Contents

- [Highlights](#highlights)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Adaptive Policy](#adaptive-policy)
- [HTTP Semantics](#http-semantics)
- [Chaos Engineering](#chaos-engineering)
- [Telemetry &amp; Cost Model](#telemetry--cost-model)
- [Benchmarks](#benchmarks)
- [Tests](#tests)
- [Research Extensions](#research-extensions)
- [Configuration](#configuration)
- [Project Layout](#project-layout)

---

## Highlights

| Capability | Implementation |
|---|---|
| Concurrency | Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) — one virtual thread per request |
| Cache engine | `ConcurrentHashMap` + custom `DoublyLinkedList`, guarded by `ReentrantReadWriteLock`; O(1) LRU with priority-aware eviction |
| Key generation | Canonical 64-bit FNV-1a hash from method + URI (query-order invariant) |
| Classification | Per-key circular `RingBuffer` → λ (req/min) + σ² (inter-arrival variance) → Hot / Bursty / One-Shot |
| Adaptive policy | TTL 300s / 60s / 10s; SWR only for bursty keys; background refresh for hot keys |
| Resilience | RFC 5861 `stale-while-revalidate` + `stale-if-error`; ETag / If-None-Match → 304 |
| Chaos | Origin fault injection: latency, 5xx failure rate, connection drops (admin API) |
| Telemetry | `/stats` (human), `/metrics` (Prometheus format), `/_health` |
| Cost model | Exact per-serve savings + deck's aggregate formula |
| Workload | Zipfian popularity × Poisson arrivals synthetic client |
| Zero runtime deps | 100% JDK standard library (JUnit/JMH jars are dev-time only) |

## Quick Start

```bash
# one-time: fetch dev-time jars (JUnit + JMH)
./scripts/fetch-libs.sh

# start origin (:9000) + proxy (:8080)
./scripts/run.sh
```

Then, in another terminal:

```bash
curl http://localhost:8080/hot/item-1      # first time:  X-Cache: MISS
curl http://localhost:8080/hot/item-1      # second time: X-Cache: HIT

# synthetic load
./scripts/workload.sh 3000 100 64

# telemetry
curl http://localhost:8080/stats
curl http://localhost:8080/metrics
```

## Architecture

```
Clients ──► ProxyServer :8080 (virtual threads) ──► OriginServer :9000
               │                                        │
               ├─ KeyGenerator (64-bit FNV-1a)          ├─ per-path JSON content
               ├─ LRUCache (CHM + DLL + RWLock)         ├─ ETag / If-None-Match
               ├─ TrafficClassifier (RingBuffers)       └─ ChaosController
               ├─ AdaptivePolicyEngine                    (latency/5xx/drop)
               ├─ CostModel + RequestStatsRegistry
               └─ TelemetryEndpoints (/stats /metrics)
```

**Per-request pipeline** (deck slide 6):

1. **Interception** — canonical 64-bit key; cache lookup
2. **Profiling** — rolling-window λ + σ² per key
3. **Adaptive policy** — TTL / SWR / priority mutation on class transitions
4. **Feedback** — stats, cost accounting, Prometheus telemetry

## Adaptive Policy

| Profile | Detection | TTL | SWR | Retention |
|---|---|---|---|---|
| **HOT** | steady &amp; frequent (low σ²) | 300 s | — (background async revalidation) | priority 3 |
| **BURSTY** | high inter-arrival variance (CV ≥ 0.8) | 60 s | 60 s (RFC 5861) | priority 2 |
| **ONE_SHOT** | frequency ≤ 2 | 10 s | — | priority 1 (evicted first) |

Eviction is **priority-aware**: when the cache is full, the eviction scan
picks a lowest-priority victim near the LRU end instead of blindly ejecting
the LRU head — hot items survive one-shot scans (no cache thrashing).

## HTTP Semantics

| Header | Meaning |
|---|---|
| `X-Cache: MISS / HIT / STALE / REVALIDATED / BYPASS / ERROR` | cache disposition |
| `X-Traffic-Class: HOT / BURSTY / ONE_SHOT` | live classification |
| `Cache-Control: public, max-age=N, stale-while-revalidate=60, stale-if-error=3600` | adaptive freshness |
| `Warning: 110` | response is stale (SWR) |
| `Warning: 111` | revalidation failed; stale served (stale-if-error) |
| `Age` | seconds since entry was stored |
| `ETag` / `If-None-Match` | RFC 7232 conditional revalidation (304 without body transfer) |

## Chaos Engineering

Origin exposes an admin endpoint:

```bash
curl -X POST http://localhost:9000/_chaos --data "mode=DEGRADED"   # 150ms latency, 25% 5xx, 5% drops
curl -X POST http://localhost:9000/_chaos --data "mode=DOWN"       # 100% 5xx
curl -X POST http://localhost:9000/_chaos --data "mode=CLEAN"      # restore
curl -X POST http://localhost:9000/_chaos --data "latencyMs=500&failRate=0.3&dropRate=0.05"
curl http://localhost:9000/_chaos                                  # status JSON
```

Behaviour under failure:

- Origin down + **cached key** → `200` stale response (`Warning: 111`)
- Origin down + **unknown key** → `503` + `Retry-After`
- Origin slow → cache keeps serving fresh hits at full speed

## Telemetry &amp; Cost Model

`/metrics` exposes Prometheus counters/gauges:

```
adaptcache_total_requests 3000
adaptcache_cache_hits 2820
adaptcache_cache_misses 180
adaptcache_stale_serves 5
adaptcache_origin_errors 2
adaptcache_hit_ratio 0.94
adaptcache_cost_savings_usd 0.037
adaptcache_requests_by_class_hot ...
```

`/stats` adds the financial view:

- **Exact savings** — per cache-serve: avoided origin compute
  (`$0.00002`/req) + avoided egress (`$0.08/GB` × body size)
- **Aggregate formula** (deck slide 8):
  `Cost Saved = (Total Requests × Hit Ratio) × (Cost_Compute + Cost_Egress)`
- **Savings vs no-cache** — exact savings as % of a no-cache baseline

## Benchmarks

JMH microbenchmarks for the hot paths (cache hit/miss/eviction, key
generation, classifier):

```bash
./scripts/bench.sh          # default benchmark set
```

## Tests

56 JUnit 5 tests: unit (keygen, LRU, ring buffer, classifier, policy, chaos,
cost model) + full integration (miss→hit, 304 revalidation, adaptive
classification, SWR stale serve, stale-if-error, chaos, telemetry, 404
passthrough).

```bash
./scripts/test.sh
```

## Configuration

All tunables live in `AdaptCacheConfig` and are overridable via environment
variables:

| Env var | Default | Meaning |
|---|---|---|
| `PROXY_PORT` / `ORIGIN_PORT` | 8080 / 9000 | listen ports |
| `CACHE_CAPACITY` | 1024 | max cache entries |
| `OBSERVATION_WINDOW_MS` | 60000 | classification window |
| `RING_BUFFER_CAPACITY` | 256 | samples per key (memory cap) |
| `BURSTY_CV_THRESHOLD` | 0.8 | variance threshold |
| `HOT_TTL_SECONDS` / `BURSTY_TTL_SECONDS` / `ONE_SHOT_TTL_SECONDS` | 300/60/10 | adaptive TTLs |
| `BURSTY_SWR_SECONDS` | 60 | RFC 5861 SWR window |
| `STALE_IF_ERROR_SECONDS` | 3600 | RFC 5861 stale-if-error |
| `EGRESS_COST_PER_GB` | 0.08 | cloud egress price |
| `ORIGIN_COMPUTE_COST_PER_REQUEST` | 0.00002 | origin compute price |

## Research Extensions

Post-MSE research track (see `PLAN.md` Part 2). All extensions are
zero-dependency, env-configurable, and covered by tests.

| # | Extension | What it does | Try it |
|---|---|---|---|
| 1 | **CACS eviction** | Novel Context-Aware Cache Scoring: `0.25·freq + 0.20·recency + 0.20·latency + 0.15·prediction + 0.10·size + 0.10·stability`. Pluggable with LFU and priority-LRU; TinyLFU admission filter | `EVICTION_POLICY=cacs ./scripts/run.sh`, `./scripts/eval.sh` |
| 3 | **Multi-level cache** | L1 edge → L2 regional → origin chaining; each level classifies independently | `./scripts/multilevel.sh` |
| 5 | **Self-tuning** | Background loop steers TTL/SWR scales + active capacity toward a hit-ratio target | `SELF_TUNING_ENABLED=true ./scripts/run.sh` |
| 6 | **Predictive warming** | EWMA demand predictor + top-K pre-fetch on boot and via `POST /_warm` | `curl -X POST localhost:8080/_warm` |
| 7 | **Explainability** | Per-entry retention reasons | `curl "localhost:8080/_explain?path=/hot/item-1"` |
| 8 | **Distributed cluster** | Consistent-hash sharding across proxy peers + Docker/K8s manifests in `deploy/` | `./scripts/cluster.sh` |
| 9 | **Green computing** | CPU/JMX energy (J) + CO₂ estimates in `/metrics` and `/stats` | `curl localhost:8080/metrics \| grep -E "energy\|co2\|cpu"` |
| 10 | **Rigorous eval** | Deterministic Zipfian head-to-head (hit ratio, avg/p50/p95, backend reqs) → `docs/eval-results.md` | `./scripts/eval.sh [cap] [N] [K]` |

Latest offline result (capacity 64, 20k reqs, 500 keys):
LFU 65.2% > CACS 62.2% > priority-LRU 61.4% ≈ TinyLFU+LRU 61.4%.
LFU wins on pure Zipfian (frequency matches popularity); CACS gains
+0.7pp over LRU from latency/prediction/stability signals.

## Project Layout

```
src/com/adaptivecache/
├── Main.java                  entry point (server | origin | proxy | workload | eval)
├── config/AdaptCacheConfig.java
├── core/                      CacheNode, DoublyLinkedList, CacheEntry, LRUCache
├── keygen/KeyGenerator.java   64-bit canonical keys (FNV-1a)
├── classifier/                TrafficClass, RingBuffer, TrafficClassifier
├── policy/                    AdaptivePolicyEngine + EvictionPolicy
│                              (PriorityAwareLru, Lfu, Cacs) + AdmissionPolicy
│                              (Always, TinyLfu)
├── predict/EwmaPredictor.java demand forecaster
├── tuning/SelfTuningController.java
├── warming/WarmingScheduler.java
├── explain/DecisionExplainer.java
├── green/EnergyMonitor.java
├── cluster/ConsistentHashRing.java
├── eval/EvaluationHarness.java
├── proxy/ProxyServer.java     the 4-phase pipeline (+peer forwarding)
├── origin/                    OriginServer, ChaosController
├── stats/                     RequestStats, RequestStatsRegistry, CostModel
├── telemetry/TelemetryEndpoints.java  (/stats /metrics /_explain /_warm)
└── workload/                  WorkloadGenerator, ZipfianGenerator
test/com/adaptivecache/        JUnit 5 suite (87 tests)
bench/com/adaptivecache/       JMH benchmarks
scripts/                       build / run / test / workload / bench / demo /
                               fetch-libs / eval / cluster / multilevel
deploy/                        Dockerfile, docker-compose.yml, K8s manifests
docs/                          demo evidence + eval-results.md
```
