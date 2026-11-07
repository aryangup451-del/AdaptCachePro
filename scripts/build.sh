#!/usr/bin/env bash
# Build AdaptCache Pro — compiles all sources into out/production/AdaptiveCache.
set -euo pipefail

cd "$(dirname "$0")/.."

JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVAC="$JAVA_HOME_DIR/bin/javac"

if [ ! -x "$JAVAC" ] && command -v javac >/dev/null 2>&1; then
    JAVAC="$(command -v javac)"
fi

echo "[build] using $JAVAC"
"$JAVAC" --version

mkdir -p out/production/AdaptiveCache
find src -name '*.java' -print0 | xargs -0 \
    "$JAVAC" --release 21 -Xlint:all -d out/production/AdaptiveCache

echo "[build] OK -> out/production/AdaptiveCache"
