#!/bin/bash
set -euo pipefail
cd /Volumes/Bot/code/JiPaiQi-Mobile
export JAVA_HOME="$(/usr/libexec/java_home -v17)"
audit_dir="$PWD/build/verification/video-state-20260913"
json_jar=$(find /Users/neo/.gradle/caches/modules-2/files-2.1/org.json/json/20240303 -name '*.jar' -print -quit)
mkdir -p "$audit_dir/old/com/jpq/mobile" "$audit_dir/old-classes" "$audit_dir/new-classes" "$audit_dir/server"
git show c6a8f0c:app/src/main/java/com/jpq/mobile/RoundSession.java > "$audit_dir/old/com/jpq/mobile/RoundSession.java"
git -C /Volumes/Bot/code/JiPaiQi-Serve archive HEAD app | tar -x -C "$audit_dir/server"
"$JAVA_HOME/bin/javac" -cp "$json_jar" -d "$audit_dir/old-classes" "$audit_dir/old/com/jpq/mobile/RoundSession.java" app/src/main/java/com/jpq/mobile/{HandReply,HandSnapshots}.java scripts/verification/RestartRecoveryProbe.java scripts/verification/VideoStateWireProbe.java
"$JAVA_HOME/bin/javac" -cp "$json_jar" -d "$audit_dir/new-classes" app/src/main/java/com/jpq/mobile/{RoundSession,HandReply,HandSnapshots}.java scripts/verification/RestartRecoveryProbe.java scripts/verification/VideoStateWireProbe.java
JPQ_DB_PATH="$audit_dir/server.sqlite3" PYTHONPATH="$audit_dir/server" /Volumes/Bot/code/JiPaiQi-Serve/.venv/bin/python -m uvicorn app.main:app --app-dir "$audit_dir/server" --host 127.0.0.1 --port 18087 > "$audit_dir/server.log" 2>&1 &
probe_pid=$!
trap 'kill "$probe_pid" 2>/dev/null || true' EXIT
/Volumes/Bot/code/JiPaiQi-Serve/.venv/bin/python - <<'PY'
import urllib.request,time
for _ in range(40):
 try: urllib.request.urlopen('http://127.0.0.1:18087/health').close();break
 except OSError: time.sleep(.1)
else: raise SystemExit('isolated server unavailable')
PY
probe_tenant="9$(date +%s)"
"$JAVA_HOME/bin/java" -cp "$audit_dir/new-classes:$json_jar" com.jpq.mobile.VideoStateWireProbe http://127.0.0.1:18087 "$probe_tenant" "$audit_dir/saved" build/verification/atomic-20260911/audit-video-840.json "${JPQ_REPLAY_MODE:-normal}"
