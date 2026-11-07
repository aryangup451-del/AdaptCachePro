#!/usr/bin/env bash
# Compile and run the JUnit 5 test suite via the console standalone launcher.
set -euo pipefail

cd "$(dirname "$0")/.."

JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
JAVAC="$JAVA_HOME_DIR/bin/javac"
JAVA="$JAVA_HOME_DIR/bin/java"
if [ ! -x "$JAVA" ]; then
    JAVAC="javac"; JAVA="java"
fi

JUNIT="lib/junit-platform-console-standalone-1.11.4.jar"
if [ ! -f "$JUNIT" ]; then
    echo "[test] missing $JUNIT — downloading..."
    curl -sL -o "$JUNIT" \
      "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
fi

./scripts/build.sh

mkdir -p out/test
find test -name '*.java' -print0 | xargs -0 \
    "$JAVAC" --release 21 -Xlint:all \
    -cp "$JUNIT:out/production/AdaptiveCache" -d out/test

"$JAVA" -jar "$JUNIT" \
    --class-path "out/production/AdaptiveCache:out/test" \
    --scan-class-path \
    --details tree
