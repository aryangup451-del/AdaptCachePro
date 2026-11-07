#!/usr/bin/env bash
# Offline head-to-head policy evaluation (Item 10).
# Usage: scripts/eval.sh [capacity] [requests] [keys]
set -euo pipefail

cd "$(dirname "$0")/.."
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVA="$JAVA_HOME_DIR/bin/java"
if [ ! -x "$JAVA" ]; then JAVA="java"; fi

if [ ! -d out/production/AdaptiveCache ]; then
    ./scripts/build.sh
fi

exec "$JAVA" -cp out/production/AdaptiveCache com.adaptivecache.Main \
    eval "${1:-64}" "${2:-20000}" "${3:-500}"
