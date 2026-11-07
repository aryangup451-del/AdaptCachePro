#!/usr/bin/env bash
# Start origin + proxy together (default demo mode).
set -euo pipefail

cd "$(dirname "$0")/.."
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVA="$JAVA_HOME_DIR/bin/java"
if [ ! -x "$JAVA" ]; then JAVA="java"; fi

if [ ! -d out/production/AdaptiveCache ]; then
    ./scripts/build.sh
fi

exec "$JAVA" -cp out/production/AdaptiveCache com.adaptivecache.Main server
