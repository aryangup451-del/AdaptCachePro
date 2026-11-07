#!/usr/bin/env bash
# Run the Zipfian/Poisson workload generator against a running proxy.
# Usage: scripts/workload.sh [totalRequests] [ratePerSec] [concurrency]
set -euo pipefail

cd "$(dirname "$0")/.."
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVA="$JAVA_HOME_DIR/bin/java"
if [ ! -x "$JAVA" ]; then JAVA="java"; fi

exec "$JAVA" -cp out/production/AdaptiveCache com.adaptivecache.Main \
    workload "${1:-2000}" "${2:-100}" "${3:-64}"
