#!/usr/bin/env bash
# Run cpu-loader, building it first when needed.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f build/cpu-loader.jar ]; then
    echo "build/cpu-loader.jar not found, building ..."
    ./build.sh
fi

exec java -jar build/cpu-loader.jar "$@"
