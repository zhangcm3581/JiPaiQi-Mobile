#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
./gradlew :app:testDebugUnitTest :app:assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/JiPaiQi-Mobile-0.4.20-debug-arm64.apk
shasum -a 256 dist/JiPaiQi-Mobile-0.4.20-debug-arm64.apk > dist/SHA256SUMS
