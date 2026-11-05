#!/bin/bash
# All credentials below belong to this disposable loopback fixture, not a camera.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${MEDIAMTX_BIN:?Set MEDIAMTX_BIN}"
: "${FFMPEG_BIN:?Set FFMPEG_BIN}"
rtsp_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
rtsp_adb="$rtsp_sdk/platform-tools/adb"
rtsp_serial="${ANDROID_SERIAL:-emulator-5554}"
rtsp_result="$PWD/tools/vendor/auth-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$rtsp_result"
rtsp_pids=()
cleanup() {
 for rtsp_pid in "${rtsp_pids[@]}"; do kill "$rtsp_pid" 2>/dev/null || true; done
 for rtsp_pid in "${rtsp_pids[@]}"; do wait "$rtsp_pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 - <<'PORT'
import socket
with socket.socket() as sock: sock.bind(('127.0.0.1',18556))
PORT
cat > "$rtsp_result/mediamtx.yml" <<'YAML'
logLevel: warn
rtspAddress: 127.0.0.1:18556
rtspTransports: [tcp]
rtmp: no
hls: no
webrtc: no
srt: no
rtspAuthMethods: [basic]
authInternalUsers:
  - user: any
    permissions:
      - action: publish
        path: h264
  - user: fixture+viewer
    pass: 'fixture@p+ss#&'
    permissions:
      - action: read
        path: h264
paths:
  h264:
YAML
./gradlew :media:assembleDebugAndroidTest
"$rtsp_adb" -s "$rtsp_serial" install -r media/build/outputs/apk/androidTest/debug/media-debug-androidTest.apk
"$MEDIAMTX_BIN" "$rtsp_result/mediamtx.yml" > "$rtsp_result/server.log" 2>&1 &
rtsp_pids+=($!)
python3 - "$!" <<'READY'
import os, socket, sys, time
for attempt in range(50):
    os.kill(int(sys.argv[1]), 0)
    try:
        with socket.create_connection(('127.0.0.1',18556), timeout=.1): break
    except OSError: time.sleep(.1)
else: raise SystemExit('Authentication fixture did not open its RTSP port')
READY
"$FFMPEG_BIN" -hide_banner -loglevel error -re -stream_loop -1 -i docs/evidence/stage2/h264-source.mp4 -c copy -rtsp_transport tcp -f rtsp rtsp://127.0.0.1:18556/h264 > "$rtsp_result/publisher.log" 2>&1 &
rtsp_pids+=($!)
sleep 1
kill -0 "${rtsp_pids[1]}"
"$rtsp_adb" -s "$rtsp_serial" shell am instrument -w -e class com.rtspmonitor.media.AuthenticationTest -e authBase rtsp://10.0.2.2:18556 com.rtspmonitor.media.test/androidx.test.runner.AndroidJUnitRunner | tee "$rtsp_result/authentication.txt"
grep -q '^OK (' "$rtsp_result/authentication.txt"
printf 'Artifacts: %s\n' "$rtsp_result"
