#!/usr/bin/env bash
# Run storage-loader, building it first when needed.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f build/storage-loader.jar ]; then
    echo "build/storage-loader.jar not found, building ..."
    ./build.sh
fi

exec java -jar build/storage-loader.jar "$@"
