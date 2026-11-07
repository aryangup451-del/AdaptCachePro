# AdaptCache Pro — Policy Evaluation

Trace: Zipfian(s=1.0, keys=500), 20000 requests, capacity 64, seed 42, virtual clock.

| policy | hit ratio | avg lat (ms) | p50 (ms) | p95 (ms) | backend reqs | admitted | rejected | evictions | MB served |
|---|---|---|---|---|---|---|---|---|---|
| lfu | 65.24% | 37.93 | 1.00 | 250.00 | 6953 | 6953 | 0 | 6889 | 3.23 |
| cacs | 62.27% | 37.60 | 1.00 | 250.00 | 7547 | 7547 | 0 | 7483 | 3.09 |
| priority-lru | 61.43% | 38.74 | 1.00 | 250.00 | 7714 | 7714 | 0 | 7650 | 3.04 |
| tinylfu+lru | 61.43% | 38.74 | 1.00 | 250.00 | 7714 | 7714 | 0 | 7650 | 3.04 |

Best hit ratio: **lfu (65.24%)**.
