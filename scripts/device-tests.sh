#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?用法：scripts/device-tests.sh 设备序列号 [测试类或方法]}"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
# Test endpoint is baked into this test APK, never read from user preferences.
./gradlew -PjpqServerUrl=http://127.0.0.1:18081 :app:assembleDebug :app:assembleDebugAndroidTest
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Device network/capture tests use an isolated sibling server, never the deployed service.
server_checkout="${JPQ_SERVER_CHECKOUT:-$(pwd)/../JiPaiQi-Serve}"
server_python="$server_checkout/.venv/bin/python"
[[ -x "$server_python" ]] || { echo "缺少测试服务端环境：$server_python"; exit 1; }
"$server_python" -c 'import socket; s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind(("127.0.0.1",18081)); s.close()'
test_state=$(mktemp -d)
JPQ_DB_PATH="$test_state/test.sqlite3" PYTHONPATH="$server_checkout" "$server_python" -m uvicorn app.main:app --host 127.0.0.1 --port 18081 > "$test_state/server.log" 2>&1 &
server_pid=$!
cleanup() { "$adb_bin" -s "$serial" reverse --remove tcp:18081 >/dev/null 2>&1 || true; kill "$server_pid" 2>/dev/null || true; }
trap cleanup EXIT
"$server_python" - <<'PYWAIT'
import time,urllib.request
for attempt in range(40):
    try:
        urllib.request.urlopen('http://127.0.0.1:18081/api/tenants',timeout=1).close()
        break
    except OSError: time.sleep(.1)
else: raise SystemExit('本地测试服务端启动失败')
PYWAIT
"$adb_bin" -s "$serial" reverse tcp:18081 tcp:18081
mkdir -p build/verification
instrument_args=(-w)
if [[ -n "${2:-}" ]]; then instrument_args+=(-e class "$2"); else instrument_args+=(-e notAnnotation androidx.test.filters.LargeTest); fi
"$adb_bin" -s "$serial" shell am instrument "${instrument_args[@]}" com.jpq.mobile.test/androidx.test.runner.AndroidJUnitRunner | tee build/verification/device-tests.txt
# am instrument may return exit 0 even when assertions fail.
rg -q '^OK \([0-9]+ tests?\)' build/verification/device-tests.txt
! rg -q 'FAILURES|Process crashed|INSTRUMENTATION_FAILED' build/verification/device-tests.txt
