#!/usr/bin/env bash
# Bundle ride_capture.js with frida-java-bridge so the Java global is
# available in Frida 17+. Output: ride_capture.compiled.js (gitignored).
#
# Run from project root:
#   bash frida-scripts/build.sh
#
# One-time setup (if /tmp/frida-build doesn't exist):
#   mkdir -p /tmp/frida-build && cd /tmp/frida-build
#   npm init -y && npm install frida-java-bridge

set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$PROJECT/frida-scripts/ride_capture.js"
BUILD="/tmp/frida-build"
OUT="$PROJECT/frida-scripts/ride_capture.compiled.js"

if [[ ! -d "$BUILD/node_modules/frida-java-bridge" ]]; then
    echo "build dir not initialized. Run:"
    echo "  mkdir -p $BUILD && cd $BUILD && npm init -y && npm install frida-java-bridge"
    exit 1
fi

cp "$SRC" "$BUILD/ride_capture.js"
cd "$BUILD"
"$PROJECT/.venv/bin/frida-compile" ride_capture.js -o "$OUT"
echo "Built: $OUT"
wc -l "$OUT"
