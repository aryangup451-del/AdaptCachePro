#!/usr/bin/env bash
# Fetch dev-time only jars (JUnit console launcher + JMH) into lib/.
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p lib

fetch() {
    local url="$1" name="$2"
    if [ -f "lib/$name" ]; then
        echo "[fetch] lib/$name already present"
        return
    fi
    echo "[fetch] $name"
    curl -sL -o "lib/$name" "$url"
}

fetch "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar" \
      "junit-platform-console-standalone-1.11.4.jar"
fetch "https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar" \
      "jmh-core-1.37.jar"
fetch "https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar" \
      "jmh-generator-annprocess-1.37.jar"
fetch "https://repo1.maven.org/maven2/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar" \
      "jopt-simple-5.0.4.jar"
fetch "https://repo1.maven.org/maven2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar" \
      "commons-math3-3.6.1.jar"

echo "[fetch] done"
