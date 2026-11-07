#!/usr/bin/env bash
# Run JMH microbenchmarks for the cache hot paths.
set -euo pipefail

cd "$(dirname "$0")/.."

JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVAC="$JAVA_HOME_DIR/bin/javac"
JAVA="$JAVA_HOME_DIR/bin/java"
if [ ! -x "$JAVA" ]; then
    JAVAC="javac"; JAVA="java"
fi

JMH_CP="lib/jmh-core-1.37.jar:lib/jmh-generator-annprocess-1.37.jar:lib/jopt-simple-5.0.4.jar:lib/commons-math3-3.6.1.jar"
for j in jmh-core-1.37 jmh-generator-annprocess-1.37 \
         jopt-simple-5.0.4 commons-math3-3.6.1; do
    if [ ! -f "lib/$j.jar" ]; then
        echo "[bench] missing lib/$j.jar — run scripts/fetch-libs.sh first"
        exit 1
    fi
done

./scripts/build.sh

mkdir -p out/bench
find bench -name '*.java' -print0 | xargs -0 \
    "$JAVAC" --release 21 \
    -cp "$JMH_CP:out/production/AdaptiveCache" \
    -processorpath "$JMH_CP" \
    -d out/bench

"$JAVA" -cp "$JMH_CP:out/bench:out/production/AdaptiveCache" \
    org.openjdk.jmh.Main \
    "${@:-com.adaptivecache.CacheBenchmark}"
