#!/usr/bin/env bash
# Two-node proxy cluster demo (Item 8): consistent-hash sharding.
# Node A owns ~half the keys, node B the rest; misses route to the owner.
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
echo "[cluster] starting origin :9000..."
setsid "$JAVA" -cp "$CP" com.adaptivecache.Main origin \
    > docs/demo-output/cluster-origin.log 2>&1 < /dev/null &
sleep 1

echo "[cluster] starting proxy A :8081 (peer B)..."
NODE_ID=node-a PEER_PROXIES=http://localhost:8082 PROXY_PORT=8081 \
    setsid "$JAVA" -cp "$CP" com.adaptivecache.Main proxy \
    > docs/demo-output/cluster-a.log 2>&1 < /dev/null &
sleep 1

echo "[cluster] starting proxy B :8082 (peer A)..."
NODE_ID=node-b PEER_PROXIES=http://localhost:8081 PROXY_PORT=8082 \
    setsid "$JAVA" -cp "$CP" com.adaptivecache.Main proxy \
    > docs/demo-output/cluster-b.log 2>&1 < /dev/null &
sleep 2

echo "[cluster] warming both nodes..."
for i in $(seq 0 19); do
    curl -s -o /dev/null "http://localhost:8081/hot/item-$i"
    curl -s -o /dev/null "http://localhost:8082/hot/item-$i"
done

echo "[cluster] sample headers (expect a mix of HIT and PEER-HIT):"
for i in 0 3 7 11 15 19; do
    printf "  item-%-2s via A: " "$i"
    curl -s -D - -o /dev/null "http://localhost:8081/hot/item-$i" \
        | grep -i '^X-Cache:' | tr -d '\r'
done

echo "[cluster] node stats:"
echo "  A peers hits: $(curl -s http://localhost:8081/metrics | grep peer_hits)"
echo "  B peers hits: $(curl -s http://localhost:8082/metrics | grep peer_hits)"
echo "[cluster] stop with: pkill -f com.adaptivecache.Main"
