#!/usr/bin/env bash
# Full end-to-end demo with evidence capture into docs/demo-output/.
set -uo pipefail

cd "$(dirname "$0")/.."
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVA="$JAVA_HOME_DIR/bin/java"
if [ ! -x "$JAVA" ]; then JAVA="java"; fi

OUT="docs/demo-output"
mkdir -p "$OUT"

# --- cleanup stale servers (avoid killing our own shell) ---
for pid in $(pgrep -f "com\.adaptivecache\.Main" 2>/dev/null || true); do
    kill "$pid" 2>/dev/null || true
done
sleep 1

./scripts/build.sh

echo "[demo] starting origin + proxy..."
setsid "$JAVA" -cp out/production/AdaptiveCache com.adaptivecache.Main server \
    > "$OUT/servers.log" 2>&1 < /dev/null &
SERVER_PID=$!

for i in $(seq 1 20); do
    if curl -sf -m 1 http://localhost:8080/_health >/dev/null 2>&1; then
        break
    fi
    sleep 0.5
done

PROXY=http://localhost:8080
ORIGIN=http://localhost:9000

echo "[demo] request lifecycle (miss -> hit -> 304)"
curl -s -D - -o /dev/null "$PROXY/hot/item-1" \
    | grep -iE "HTTP/|x-cache|x-traffic-class" > "$OUT/lifecycle-1-miss.txt"
curl -s -D - -o /dev/null "$PROXY/hot/item-1" \
    | grep -iE "HTTP/|x-cache|x-traffic-class" > "$OUT/lifecycle-2-hit.txt"
ETAG=$(curl -s -D - -o /dev/null "$PROXY/hot/item-1" \
    | grep -i '^ETag:' | tr -d '\r' | cut -d' ' -f2)
curl -s -D - -o /dev/null -H "If-None-Match: $ETAG" "$PROXY/hot/item-1" \
    | grep -iE "HTTP/|x-cache" > "$OUT/lifecycle-3-304.txt"
cat "$OUT/lifecycle-1-miss.txt" "$OUT/lifecycle-2-hit.txt" \
    "$OUT/lifecycle-3-304.txt"

echo "[demo] adaptive classification (spiky -> BURSTY + SWR header)"
for i in 1 2 3; do curl -s -o /dev/null "$PROXY/bursty/item-7"; done
sleep 2
for i in 1 2 3; do curl -s -o /dev/null "$PROXY/bursty/item-7"; done
curl -s -D - -o /dev/null "$PROXY/bursty/item-7" \
    | grep -iE "x-traffic-class|cache-control" > "$OUT/adaptive-bursty.txt"
cat "$OUT/adaptive-bursty.txt"

echo "[demo] Zipfian + Poisson workload (3000 req @ 100/s)"
"$JAVA" -cp out/production/AdaptiveCache com.adaptivecache.Main \
    workload 3000 100 64 > "$OUT/workload.txt" 2>&1
tail -n 10 "$OUT/workload.txt"

echo "[demo] chaos: origin DOWN -> stale-if-error on expired key"
curl -s -o /dev/null "$PROXY/static/chaos-demo"
echo "  waiting 11s for ONE_SHOT TTL (10s) expiry..."
sleep 11
curl -s -X POST "$ORIGIN/_chaos" --data "mode=DOWN" >/dev/null
curl -s -D - -o /dev/null "$PROXY/static/chaos-demo" \
    | grep -iE "HTTP/|x-cache|warning" > "$OUT/chaos-stale.txt"
curl -s -o /dev/null -w "  fresh key while down -> HTTP %{http_code}\n" \
    "$PROXY/static/never-seen"
curl -s -X POST "$ORIGIN/_chaos" --data "mode=CLEAN" >/dev/null
cat "$OUT/chaos-stale.txt"

echo "[demo] telemetry snapshot"
curl -s "$PROXY/stats" > "$OUT/stats.txt"
curl -s "$PROXY/metrics" > "$OUT/metrics.txt"
grep -E "Total requests|Hit ratio|Origin offload|Exact savings|HOT|BURSTY" \
    "$OUT/stats.txt"

echo "[demo] stopping servers (pid $SERVER_PID)"
kill "$SERVER_PID" 2>/dev/null || true
echo "[demo] evidence written to $OUT/"
ls "$OUT"
