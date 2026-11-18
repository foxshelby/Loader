#!/usr/bin/env bash
# Build storage-loader with plain javac (JDK 8+). No Maven required.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v javac >/dev/null 2>&1; then
    echo "[ERROR] javac not found. Install JDK 8+ and add its bin directory to PATH." >&2
    exit 1
fi

rm -rf build
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
printf 'Main-Class: com.storageloader.Main\n' > build/manifest.txt

echo "Compiling for Java 8 ..."
if ! javac --release 8 -encoding UTF-8 -d build/classes @build/sources.txt 2>/dev/null; then
    echo "This javac has no --release 8 support, retrying with -source/-target 1.8 ..."
    javac -source 1.8 -target 1.8 -encoding UTF-8 -d build/classes @build/sources.txt
fi

jar cfm build/storage-loader.jar build/manifest.txt -C build/classes .
echo
echo "Build OK: build/storage-loader.jar"
