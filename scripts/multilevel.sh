#!/usr/bin/env bash
# Multi-level cache demo (Item 3): L1 edge -> L2 regional -> origin.
# Each level classifies and adapts independently.
set -uo pipefail

cd "$(dirname "$0")/.."
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVA="$JAVA_HOME_DIR/bin/java"
if [ ! -x "$JAVA" ]; then JAVA="java"; fi

./scripts/build.sh

for pid in $(pgrep -f "com\.adaptivecache\.Main" 2>/dev/null || true); do
    kill "$pid" 2>/dev/null || true
done
sleep 1

CP=out/production/AdaptiveCache
echo "[multilevel] origin :9000..."
setsid "$JAVA" -cp "$CP" com.adaptivecache.Main origin \
    > docs/demo-output/ml-origin.log 2>&1 < /dev/null &
sleep 1

echo "[multilevel] L2 regional :8082 (big cache, upstream=origin)..."
CACHE_CAPACITY=2048 PROXY_PORT=8082 \
    setsid "$JAVA" -cp "$CP" com.adaptivecache.Main proxy \
    > docs/demo-output/ml-l2.log 2>&1 < /dev/null &
sleep 1

echo "[multilevel] L1 edge :8081 (small cache, upstream=L2)..."
CACHE_CAPACITY=128 PROXY_PORT=8081 \
    ORIGIN_BASE_URL=http://localhost:8082 \
    setsid "$JAVA" -cp "$CP" com.adaptivecache.Main proxy \
    > docs/demo-output/ml-l1.log 2>&1 < /dev/null &
sleep 2

echo "[multilevel] driving traffic at L1..."
PROXY_PORT=8081 "$JAVA" -cp "$CP" com.adaptivecache.Main workload \
    1500 100 32 > docs/demo-output/ml-workload.txt 2>&1 || true

echo "[multilevel] L1 stats:"; curl -s http://localhost:8081/stats \
    | grep -E "Total requests|Hit ratio" || true
echo "[multilevel] L2 stats:"; curl -s http://localhost:8082/stats \
    | grep -E "Total requests|Hit ratio" || true
echo "[multilevel] stop with: pkill -f com.adaptivecache.Main"
