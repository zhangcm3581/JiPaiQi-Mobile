#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?用法：scripts/device-tests.sh 设备序列号}"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p build/verification
"$adb_bin" -s "$serial" shell am instrument -w com.jpq.mobile.test/androidx.test.runner.AndroidJUnitRunner | tee build/verification/device-tests.txt
# am instrument may return exit 0 even when assertions fail.
rg -q '^OK \([0-9]+ tests?\)' build/verification/device-tests.txt
! rg -q 'FAILURES|Process crashed|INSTRUMENTATION_FAILED' build/verification/device-tests.txt
