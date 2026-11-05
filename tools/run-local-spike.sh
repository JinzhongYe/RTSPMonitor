#!/bin/bash
# Synthetic sources only; all host listeners bind loopback.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${MEDIAMTX_BIN:?Set MEDIAMTX_BIN to the MediaMTX executable}"
: "${FFMPEG_BIN:?Set FFMPEG_BIN to a FFmpeg executable with RTSP/MP4/WAV support}"
rtsp_mode="${1:-record}"
case "$rtsp_mode" in record|udp|udp-forwarded|playback|audio|recovery|background|playback-isolation|notifications-denied|resilience|long|vlc) ;; *) echo "Unknown test mode" >&2; exit 2;; esac
rtsp_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
rtsp_adb="$rtsp_sdk/platform-tools/adb"
rtsp_serial="${ANDROID_SERIAL:-emulator-5554}"
rtsp_result="$PWD/tools/vendor/spike-$(date +%Y%m%d-%H%M%S)"
rtsp_runner=com.rtspmonitor.media.test
rtsp_pids=()
rtsp_restore_notifications=false
mkdir -p "$rtsp_result/fixtures"
cleanup() {
 if [ "$rtsp_restore_notifications" = true ]; then
  "$rtsp_adb" -s "$rtsp_serial" shell pm clear-permission-flags com.rtspmonitor.app android.permission.POST_NOTIFICATIONS user-set user-fixed || true
  "$rtsp_adb" -s "$rtsp_serial" shell pm grant com.rtspmonitor.app android.permission.POST_NOTIFICATIONS || true
 fi
 "$rtsp_adb" -s "$rtsp_serial" pull /sdcard/Android/data/com.rtspmonitor.media.test/files/spike-results "$rtsp_result/" >/dev/null 2>&1 || true
 for rtsp_pid in "${rtsp_pids[@]+${rtsp_pids[@]}}"; do kill "$rtsp_pid" 2>/dev/null || true; done
 for rtsp_pid in "${rtsp_pids[@]+${rtsp_pids[@]}}"; do wait "$rtsp_pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
run_test() {
 local rtsp_class="$1" rtsp_log="$2"
 shift 2
 "$rtsp_adb" -s "$rtsp_serial" shell am instrument -w -e class "$rtsp_class" "$@" "$rtsp_runner/androidx.test.runner.AndroidJUnitRunner" | tee "$rtsp_result/$rtsp_log"
 grep -q '^OK (' "$rtsp_result/$rtsp_log"
}
python3 - <<'CHECK_PORTS'
import socket
for kind, ports in [(socket.SOCK_STREAM,[18554,19997]),(socket.SOCK_DGRAM,[18000,18001])]:
    for port in ports:
        with socket.socket(socket.AF_INET,kind) as sock: sock.bind(('127.0.0.1',port))
CHECK_PORTS
./gradlew :media:assembleDebugAndroidTest
"$rtsp_adb" -s "$rtsp_serial" install -r media/build/outputs/apk/androidTest/debug/media-debug-androidTest.apk
run_test com.rtspmonitor.media.FixtureTest fixtures.log -e generateFixtures true
if [ "$rtsp_mode" = audio ]; then run_test com.rtspmonitor.media.AudioFixtureTest audio-fixture.log -e generateFixtures true; fi
"$rtsp_adb" -s "$rtsp_serial" pull /sdcard/Android/data/com.rtspmonitor.media.test/files/fixtures/. "$rtsp_result/fixtures/"
run_test 'com.rtspmonitor.media.MediaStoreVideoTest#h264PublishAndDelete,com.rtspmonitor.media.MediaStoreVideoTest#h265PublishAndDelete,com.rtspmonitor.media.MediaStoreVideoTest#invalidContainerIsNotPublished' mediastore.log -e useFixtures true
cat > "$rtsp_result/mediamtx.yml" <<'YAML'
logLevel: info
rtspAddress: 127.0.0.1:18554
rtpAddress: 127.0.0.1:18000
rtcpAddress: 127.0.0.1:18001
rtmp: no
hls: no
webrtc: no
srt: no
api: yes
apiAddress: 127.0.0.1:19997
paths:
  all_others:
YAML
"$MEDIAMTX_BIN" "$rtsp_result/mediamtx.yml" > "$rtsp_result/server.log" 2>&1 &
rtsp_pids+=($!)
for rtsp_attempt in $(seq 1 50); do
 if curl -fsS http://127.0.0.1:19997/v3/paths/list > "$rtsp_result/paths.json" 2>/dev/null; then break; fi
 kill -0 "${rtsp_pids[0]}"; sleep 0.1
done
for rtsp_codec in h264 h265; do
 "$FFMPEG_BIN" -hide_banner -loglevel error -re -stream_loop -1 -i "$rtsp_result/fixtures/$rtsp_codec.mp4" -c copy -rtsp_transport tcp -f rtsp "rtsp://127.0.0.1:18554/$rtsp_codec" > "$rtsp_result/publish-$rtsp_codec.log" 2>&1 &
 rtsp_pids+=($!)
done
sleep 1
case "$rtsp_mode" in
 record)
  run_test 'com.rtspmonitor.media.RtspRecordingTest#h264Tcp,com.rtspmonitor.media.RtspRecordingTest#h265Tcp,com.rtspmonitor.media.RtspRecordingTest#twoRecordersRemainIndependentWhenOneStops,com.rtspmonitor.media.RtspRecordingTest#sourceChangesAreUnionedWithinOneSegment' recording.log -e rtspBase rtsp://10.0.2.2:18554 ;;
 udp)
  run_test 'com.rtspmonitor.media.RtspRecordingTest#h264Udp,com.rtspmonitor.media.RtspRecordingTest#h265Udp' udp.log -e rtspBase rtsp://10.0.2.2:18554 ;;
 udp-forwarded|playback)
  ANDROID_SERIAL="$rtsp_serial" python3 tools/emulator-udp-proxy.py > "$rtsp_result/udp-routes.log" 2>&1 &
  rtsp_pids+=($!); sleep 0.5
  if [ "$rtsp_mode" = playback ]; then rtsp_class=com.rtspmonitor.media.PlaybackTest
  else rtsp_class='com.rtspmonitor.media.RtspRecordingTest#h264Udp,com.rtspmonitor.media.RtspRecordingTest#h265Udp'; fi
  run_test "$rtsp_class" "$rtsp_mode.log" -e rtspBase rtsp://10.0.2.2:18555 -e useFixtures true ;;
 audio)
  python3 - "$rtsp_result/fixtures/alaw.wav" <<'WAV'
from pathlib import Path
import struct,sys
payload=bytes([0xd5])*48000
fmt=struct.pack('<HHIIHHH',6,1,8000,8000,1,8,0)
body=b'WAVE'+b'fmt '+struct.pack('<I',len(fmt))+fmt+b'fact'+struct.pack('<II',4,48000)+b'data'+struct.pack('<I',len(payload))+payload
Path(sys.argv[1]).write_bytes(b'RIFF'+struct.pack('<I',len(body))+body)
WAV
  for rtsp_codec in h264 h265; do
   "$FFMPEG_BIN" -hide_banner -loglevel error -re -stream_loop -1 -i "$rtsp_result/fixtures/$rtsp_codec.mp4" -re -stream_loop -1 -i "$rtsp_result/fixtures/audio.m4a" -map 0:v:0 -map 1:a:0 -c copy -rtsp_transport tcp -f rtsp "rtsp://127.0.0.1:18554/${rtsp_codec}aac" > "$rtsp_result/publish-${rtsp_codec}aac.log" 2>&1 &
   rtsp_pids+=($!)
  done
  "$FFMPEG_BIN" -hide_banner -loglevel error -re -stream_loop -1 -i "$rtsp_result/fixtures/h264.mp4" -re -stream_loop -1 -i "$rtsp_result/fixtures/alaw.wav" -map 0:v:0 -map 1:a:0 -c copy -rtsp_transport tcp -f rtsp rtsp://127.0.0.1:18554/h264alaw > "$rtsp_result/publish-alaw.log" 2>&1 &
  rtsp_pids+=($!); sleep 1
  run_test 'com.rtspmonitor.media.RtspRecordingTest#h264AacCopy,com.rtspmonitor.media.RtspRecordingTest#h265AacCopy,com.rtspmonitor.media.RtspRecordingTest#alawIsDroppedWithWarning' audio.log -e rtspBase rtsp://10.0.2.2:18554 ;;
 recovery)
  "$FFMPEG_BIN" -hide_banner -loglevel error -y -i "$rtsp_result/fixtures/h264.mp4" -c copy -movflags +frag_keyframe+empty_moov+default_base_moof "$rtsp_result/fixtures/recovery-fmp4.mp4"
  "$rtsp_adb" -s "$rtsp_serial" push "$rtsp_result/fixtures/recovery-fmp4.mp4" /sdcard/Android/data/com.rtspmonitor.media.test/files/fixtures/recovery-fmp4.mp4
  run_test 'com.rtspmonitor.media.RecoveryTest,com.rtspmonitor.media.WriteFailureTest' recovery.log -e useFixtures true -e rtspBase rtsp://10.0.2.2:18554 ;;
 vlc)
  run_test com.rtspmonitor.media.VlcProbeTest vlc.log -e rtspBase rtsp://10.0.2.2:18554 -e rtspApi http://10.0.2.2:19997 ;;
 background|playback-isolation|notifications-denied|resilience|long)
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  "$rtsp_adb" -s "$rtsp_serial" install -r app/build/outputs/apk/debug/app-debug.apk
  "$rtsp_adb" -s "$rtsp_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  "$rtsp_adb" -s "$rtsp_serial" shell pm grant com.rtspmonitor.app android.permission.POST_NOTIFICATIONS
  if [ "$("$rtsp_adb" -s "$rtsp_serial" shell getprop ro.build.version.sdk | tr -d '\r')" -ge 37 ]; then
   "$rtsp_adb" -s "$rtsp_serial" shell pm grant com.rtspmonitor.app android.permission.ACCESS_LOCAL_NETWORK
  fi
  rtsp_runner=com.rtspmonitor.app.test
  case "$rtsp_mode" in
   playback-isolation) run_test com.rtspmonitor.app.PlaybackIsolationTest playback-isolation.log -e rtspBase rtsp://10.0.2.2:18554 ;;
   background) run_test com.rtspmonitor.app.BackgroundRecordingTest background.log -e rtspBase rtsp://10.0.2.2:18554 ;;
   notifications-denied)
    rtsp_restore_notifications=true
    "$rtsp_adb" -s "$rtsp_serial" shell pm revoke com.rtspmonitor.app android.permission.POST_NOTIFICATIONS
    "$rtsp_adb" -s "$rtsp_serial" shell pm set-permission-flags com.rtspmonitor.app android.permission.POST_NOTIFICATIONS user-set user-fixed
    run_test 'com.rtspmonitor.app.BackgroundRecordingTest#recordsWithNotificationPermissionDenied' notifications-denied.log -e notificationPermission denied -e rtspBase rtsp://10.0.2.2:18554
    ;;
   resilience) python3 tools/run-resilience-spike.py --adb "$rtsp_adb" --serial "$rtsp_serial" --output "$rtsp_result/resilience" ;;
   long)
    run_test com.rtspmonitor.app.LongRecordingTest long.log -e longRecording true -e rtspBase rtsp://10.0.2.2:18554
    "$rtsp_adb" -s "$rtsp_serial" pull /sdcard/Android/data/com.rtspmonitor.app/files/long-results "$rtsp_result/"
    ;;
  esac ;;
esac
printf 'Artifacts: %s\n' "$rtsp_result"
