# AdaptCache Pro — Implementation Plan (RALPLAN-DR Consensus)

**Status:** APPROVED for execution
**Baseline:** MSE Review deck `AdaptCache_Pro_MSE_Review.pptx` (slides 1–10)
**Target:** 100% completion of the described system

---

## 1. PLANNER — Scope, Breakdown, Acceptance Criteria

### Intent & Scope
Complete the AdaptCache Pro adaptive HTTP cache / reverse proxy in **Java 21** (zero
runtime third-party dependencies). The partial codebase already contains a basic
LRU cache + proxy + origin skeleton; everything described in the MSE deck must be
implemented and verified.

### In Scope
1. Reverse proxy core (Java 21 Virtual Threads, `com.sun.net.httpserver`)
2. Custom thread-safe LRU cache (`ConcurrentHashMap` + custom `DoublyLinkedList`,
   guarded by `ReentrantReadWriteLock`)
3. 64-bit deterministic canonical key generation from URI + method
4. Real-time statistical classifier (bounded rolling ring buffers; frequency λ and
   inter-arrival variance σ²) → **Hot / Bursty / One-Shot**
5. Adaptive policy engine (TTL 300s/60s/10s, eviction priority, SWR flags)
6. RFC 5861 Stale-While-Revalidate + stale-if-error + RFC 2616/7232 ETag /
   If-None-Match conditional revalidation
7. Origin server with per-path content + ChaosController fault injection
   (latency, 5xx errors, drops, timeouts)
8. Telemetry: `/stats` (human) + `/metrics` (Prometheus text format), StatsRegistry
9. Quantifiable cost model (egress + origin compute savings, $)
10. Synthetic workload generator: Zipfian popularity + Poisson arrivals
11. JUnit 5 test suite (unit + integration), runnable via console standalone launcher
12. Build / run / test / demo scripts, README, IntelliJ module update

### Out of Scope (non-goals)
- Distributed/multi-node cache (single node, as deck specifies)
- HTTPS/TLS termination (HTTP/1.1 only, as deck's stack specifies)
- Maven/Gradle build (project is IntelliJ-based; plain `javac` scripts keep
  zero-toolchain friction; JUnit/JMH jars are dev-time only, fetched into `lib/`)

### User Stories → Acceptance Criteria
| # | Story | Acceptance Criteria (testable) |
|---|-------|-------------------------------|
| U1 | Client request is served from cache | 2nd identical request returns cached body with `X-Cache: HIT`; hit counters increment |
| U2 | Miss forwarded to origin | `X-Cache: MISS`; body cached; origin receives exactly one upstream request |
| U3 | Keys are canonical 64-bit | Same URI with different query ordering / trailing slash variations map to same key; different methods differ |
| U4 | LRU evicts least-recently-used | Capacity 3: insert a,b,c; read a; insert d ⇒ b evicted |
| U5 | Thread-safe cache | 100k concurrent mixed get/put via virtual threads completes without corruption; no exceptions; size ≤ capacity |
| U6 | Hot classification | Key with ≥3 requests, low inter-arrival variance ⇒ class HOT, TTL 300s |
| U7 | Bursty classification | Key with high inter-arrival variance (spikes) ⇒ class BURSTY, TTL 60s, `stale-while-revalidate=60` in Cache-Control |
| U8 | One-shot classification | Key with frequency ≤ 2 ⇒ class ONE_SHOT, TTL 10s, lowest eviction priority |
| U9 | SWR serving | Bursty key expired ⇒ immediate stale response with `Warning: 110` + `Age` header, async revalidation in background |
| U10 | stale-if-error | Origin down/5xx + fresh miss ⇒ eligible stale entry served (200) instead of 502 |
| U11 | Conditional revalidation | Hot key with `If-None-Match` matching ETag ⇒ origin returns 304, entry TTL refreshed without body transfer |
| U12 | Chaos injection | Enabling latency/failure on origin degrades upstream; proxy keeps serving cache; enabling 5xx on miss returns 503 only when no stale entry |
| U13 | Telemetry | `/metrics` exposes Prometheus counters (requests/hits/misses per class); `/stats` shows per-key table + hit ratio |
| U14 | Cost model | `CostModel` computes savings = totalRequests × hitRatio × (egressCostPerByte + computeCostPerRequest); deterministic for given inputs |
| U15 | Workload generator | Zipfian skew: top key requested far more than tail; Poisson arrivals produce mean rate ≈ configured λ; run completes and prints summary |
| U16 | Build reproducibility | `./scripts/build.sh` → exit 0, zero warnings-as-errors off; `./scripts/test.sh` → all tests green; `./scripts/demo.sh` → E2E output with hit ratio + cost savings |

### Phased Breakdown
- **Phase 1** Consolidate & restructure existing files (remove stray root
  `src/CacheNode.java`, `src/LRUCache.java`; move `CacheNode`/`DoublyLinkedList`
  into `core`; keep package `com.adaptivecache`)
- **Phase 2** Cache engine: `CacheEntry`, thread-safe `LRUCache`
  (ReentrantReadWriteLock), eviction callbacks
- **Phase 3** `KeyGenerator` (FNV-1a 64-bit over canonical method+URI)
- **Phase 4** `RingBuffer` + `TrafficClassifier`
- **Phase 5** `AdaptivePolicyEngine` + `TrafficClass`
- **Phase 6** `ProxyServer` rewrite (full 4-phase pipeline)
- **Phase 7** `OriginServer` rewrite + `ChaosController`
- **Phase 8** `RequestStats` upgrade, `StatsRegistry`, `/stats`, `/metrics`, `CostModel`
- **Phase 9** `WorkloadGenerator` + `ZipfianGenerator`
- **Phase 10** JUnit 5 suite + launcher
- **Phase 11** Scripts, README, `.iml`
- **Phase 12** Full verification + E2E evidence capture

---

## 2. ARCHITECT — Design & Contracts

### Component Diagram
```
Clients (WorkloadGenerator / curl)
        │  HTTP
        ▼
┌───────────────────────────── ProxyServer :8080 ─────────────────────────────┐
│  Virtual-thread executor                                                     │
│  1) KeyGenerator.canonical(method, URI) → 64-bit key (FNV-1a)               │
│  2) LRUCache.get(key) → CacheEntry | null                                    │
│     ├─ fresh hit           → serve, recordStats(hit)                        │
│     ├─ stale hit (SWR)     → serve stale + Warning:110, async revalidate    │
│     └─ miss                → forward to origin                              │
│  3) TrafficClassifier.observe(key, now) → per-key RingBuffer                │
│  4) AdaptivePolicyEngine.apply(entry, class) → TTL/priority/SWR flags       │
│  5) CostModel + StatsRegistry record every request                          │
└──────────────────────────────────────────────────────────────────────────────┘
        │  java.net.http.HttpClient (async, pooled)
        ▼
┌─────────────────────────────── OriginServer :9000 ───────────────────────────┐
│  /hot/*, /bursty/*, /oneshot/*, /static/*   (content per workload profile)  │
│  ETag support + If-None-Match → 304                                         │
│  ChaosController:  POST /_chaos {latencyMs, failRate, dropRate, mode}       │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Key Contracts
- **CacheEntry**: `key, value(byte[]), etag, storedAt, expiresAt, swrWindowMs,
  trafficClass, priority, accessCount, revalidating(flag)`
- **LRUCache** API:
  - `CacheEntry get(String key)` — read-locked, bumps recency
  - `void put(String key, CacheEntry e)` — write-locked, evicts LRU at capacity
    (never evicts `revalidating` hot entries if a lower-priority victim exists)
  - `CacheEntry peek(String key)` — no recency bump (for stats)
  - `remove(String key)`, `size()`, `clear()`, `EvictionListener onEvict`
- **TrafficClassifier.observe(key, nowMillis) → ClassificationReport**
  (class, freqPerMin λ, variance σ², isWarm)
- **AdaptivePolicyEngine.policyFor(report) → Policy**
  (ttlSeconds, swrSeconds, priority, backgroundRefresh)
- **Priority order**: HOT(3) > BURSTY(2) > ONE_SHOT(1); eviction prefers lowest
  priority first, then LRU within priority
- **HTTP semantics** (RFC 5861 / 7234):
  - `Cache-Control: public, max-age=<ttl>, stale-while-revalidate=<swr>` (bursty),
    `stale-if-error=3600` (all)
  - `Warning: 110 - "Response is Stale"` + `Age: <n>` on stale serves
  - `ETag` + `If-None-Match` (RFC 7232 conditional revalidation)
  - Response headers: `X-Cache: HIT|MISS|STALE|REVALIDATED`,
    `X-Traffic-Class: HOT|BURSTY|ONE_SHOT`
- **Concurrency**: all shared mutable state (LRU list, stats maps, ring buffers)
  guarded; classifier map keyed per canonical key; bounded (pruned on eviction)

### Technology Decisions
- Java 21 (Virtual Threads via `Executors.newVirtualThreadPerTaskExecutor()`)
- `com.sun.net.httpserver.HttpServer` + `java.net.http.HttpClient` — zero deps
- JUnit 5 console-standalone + JMH jars downloaded into `lib/` (dev-time only)
- Ports: proxy 8080, origin 9000 (unchanged from skeleton)

---

## 3. CRITIC — Pre-Mortem & Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| DoublyLinkedList races (double-remove / lost links) under concurrent get+evict | High | Data corruption | All list mutations inside LRUCache write-lock; remove() idempotent; single owner of list |
| `com.sun.net.httpserver` writes after response closed (SWR async revalidation threads) | Medium | Exceptions/log spam | Per-exchange state machine; only one body write per exchange; revalidation path never touches closed exchange |
| Unbounded memory: stats/ring per unique key | Medium | OOM on key explosion | RingBuffer fixed capacity; classifier entries pruned via EvictionListener; stats map bounded LRU |
| Virtual threads + `Thread.sleep` misuse blocking carrier | Low | Perf | Use scheduled executor for SWR background refresh, not raw threads |
| ETag/304 path double-serving body | Medium | Protocol violation | Strict flow: revalidate → update entry only → serve existing body |
| Stale-if-error loop when origin down and no stale | Medium | 502 instead of graceful | Serve `503 + Retry-After` only when no stale candidate; cache negative? No — keep correct |
| Query-order key collision vs distinct pages | Low | Wrong cache hit | Canonicalization: sort query params, strip trailing slash, lower-case host — deterministic |
| Test flakiness from wall-clock timing (variance/classification) | Medium | CI noise | Classifier injectable clock (`LongSupplier nowMillis`); tests use fake clock |
| Port conflicts on demo machine | Low | Demo fail | Config override via env `PROXY_PORT`/`ORIGIN_PORT`; scripts check availability |
| 64-bit hash collision | Negligible | Wrong entry | Accepted (deck specifies 64-bit); full canonical string retained for reporting |

**Decision gate:** No critical trade-offs remain open — all choices follow the
deck. Proceeding to phased execution with verification at each phase boundary.

---

## 4. Verification Strategy
- `javac --release 21 -Xlint:all` compile with zero errors (warnings documented)
- JUnit 5 console launcher: all tests pass
- E2E `demo.sh`: start origin → start proxy → workload → `/stats` + `/metrics`
  snapshot showing hit ratio ≥ 80% on skewed workload and positive cost savings

---

# PART 2 — Research Extensions (post-MSE scope)

Approved additions, each mapped to the requested research direction.
Depth decisions are documented per item; anything marked
"documented-only" ships as runbooks/manifests rather than code.

## Item 1 — Novel CACS eviction (Context-Aware Adaptive Cache Scoring)
Full implementation. `EvictionPolicy` becomes pluggable in `LRUCache`
(`PriorityAwareLru` = current behaviour, `Lfu`, `Cacs`). CACS scores every
candidate with a weighted, normalised sum:
`score = 0.25·freq + 0.20·recency + 0.20·backendLatencySaved
+ 0.15·predictedPopularity + 0.10·sizeEfficiency + 0.10·stability`.
New `CacheEntry` signals: per-key backend latency (measured on miss),
EWMA popularity prediction, size. Evaluated head-to-head vs LRU/LFU/
TinyLFU in the eval harness. ML-grade predictors (transformers) and full
ARC are documented-only (out of scope for a zero-dependency JDK build).

## Item 3 — Multi-level intelligent cache
Implemented as chained proxy instances: L1 edge (small/fast) → L2 regional
(large) → origin, via `scripts/multilevel.sh`. Each level classifies and
adapts independently; decision propagation (per-level `X-Cache` headers +
aggregated stats) is documented in README. No new protocol code needed —
`ProxyServer` already accepts an arbitrary upstream URL.

## Item 5 — Self-tuning cache
Implemented: `SelfTuningController` background loop observes hit ratio,
latency and JVM memory pressure, and adjusts TTL scale factor, SWR scale
and logical `activeCapacity` within configured bounds. No manual tuning;
state visible in `/stats`. True RL-based tuning is documented-only.

## Item 6 — Predictive cache warming
Implemented: `WarmingScheduler` pre-fetches top-K predicted keys on boot
and on demand (`POST /_warm`), using the EWMA predictor + stats ranking.
"Tomorrow's traffic" forecasting hooks are documented-only.

## Item 7 — Explainable decisions
Implemented: `DecisionExplainer` renders per-entry reasons
("retained: HOT, 98% hit rate, 240ms backend latency saved, …") via
`GET /_explain?path=…` and inside `/stats`.

## Item 8 — Cloud-native distributed cache
Implemented: `ConsistentHashRing` + peer forwarding (`PEER_PROXIES`,
`NODE_ID`): a proxy forwards misses it doesn't own to the owning peer.
Ships `deploy/Dockerfile`, `deploy/docker-compose.yml`,
`deploy/k8s-{deployment,service,hpa}.yaml` + `scripts/cluster.sh`
(2-node local demo). Live autoscaling evaluation is documented-only.

## Item 9 — Green computing
Implemented: `EnergyMonitor` samples process CPU (JMX), estimates power
(linear TDP model, `NODE_TDP_WATTS`) into joules + CO₂ (grid factor),
plus backend-CPU-saved from offloaded requests. Exposed in `/metrics`
and `/stats`. Hardware power measurement is documented-only.

## Item 10 — Rigorous evaluation
Implemented: `EvaluationHarness` replays an identical seeded Zipfian trace
against LRU / LFU / CACS / TinyLFU+LRU with a per-profile backend-latency
model; reports hit ratio, avg/p50/p95 latency, backend requests, bytes,
memory; writes `docs/eval-results.md`. Run via `Main eval` /
`scripts/eval.sh`. Redis/NGINX/Varnish external baselines: runbook in
README (same workload client), attempted opportunistically if time
permits.

## 5-star architecture mapping
Request Analyzer = ProxyServer pipeline · Traffic Predictor =
EwmaPredictor · Adaptive Cache Engine = LRUCache + EvictionPolicy(CACS)
+ SelfTuningController + WarmingScheduler + AdaptivePolicyEngine +
DecisionExplainer · Distributed Cache Cluster = peer ring + K8s
manifests · Origin Servers = OriginServer + ChaosController.
