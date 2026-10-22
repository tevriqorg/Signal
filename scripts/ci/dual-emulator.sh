#!/usr/bin/env bash
set -euo pipefail
mkdir -p dual-evidence
# Build only on GitHub's hosted runner, before booting the second emulator.
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace > dual-evidence/build.log 2>&1
export SIGNAL_ENROLL_TOKEN=synthetic-test-enrollment-secret
export SIGNAL_DATA_DIR="$RUNNER_TEMP/signal-dual-data"
export SIGNAL_BIND=127.0.0.1
python3 server/signal_server.py > dual-evidence/server.log 2>&1 &
srv=$!
trap 'kill "$srv" 2>/dev/null || true; adb -s emulator-5556 emu kill >/dev/null 2>&1 || true' EXIT
# Reuse the installed system image, create a distinct AVD with its own writable userdata.
avdmanager create avd --force --name signal-peer --package 'system-images;android-35;google_apis;x86_64' --device pixel_6 <<< no
"$ANDROID_HOME/emulator/emulator" -avd signal-peer -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 1536 > dual-evidence/emulator-B.log 2>&1 &
for serial in emulator-5554 emulator-5556; do
  adb -s "$serial" wait-for-device
  for attempt in $(seq 1 180); do
    [[ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break
    sleep 2
  done
  [[ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]
  adb -s "$serial" shell settings put global stay_on_while_plugged_in 7
  adb -s "$serial" shell settings put system screen_off_timeout 1800000
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
  adb -s "$serial" shell wm dismiss-keyguard
  adb -s "$serial" shell input keyevent 82
  adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb -s "$serial" reverse tcp:8765 tcp:8765
  adb -s "$serial" shell pm grant com.wb.signalpro android.permission.READ_MEDIA_IMAGES
  adb -s "$serial" shell pm grant com.wb.signalpro android.permission.READ_MEDIA_VIDEO
  adb -s "$serial" shell getprop > "dual-evidence/$serial-properties.txt"
done
adb devices -l > dual-evidence/devices.txt
[[ $(adb devices | grep -c '^emulator-.*device$') -eq 2 ]]
phase() {
  local role="$1" task="$2" serial=emulator-5554
  [[ "$role" == B ]] && serial=emulator-5556
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
  adb -s "$serial" shell wm dismiss-keyguard
  adb -s "$serial" shell am instrument -w -r -e class com.wb.signalpro.photos.DualEmulatorInstrumentedTest -e role "$role" -e phase "$task" com.wb.signalpro.test/androidx.test.runner.AndroidJUnitRunner | tee "dual-evidence/$role-$task.txt"
  adb -s "$serial" logcat -d > "dual-evidence/$role-$task-logcat.txt"
  adb -s "$serial" shell screencap -p /sdcard/dual-phase.png
  adb -s "$serial" pull /sdcard/dual-phase.png "dual-evidence/$role-$task.png"
  grep -q 'OK (1 test)' "dual-evidence/$role-$task.txt"
  ! grep -qE 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "dual-evidence/$role-$task.txt"
}
command -v ffmpeg >/dev/null || { sudo apt-get update -qq; sudo apt-get install -y ffmpeg; }
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' -t 36 -c:v libx264 -preset ultrafast -b:v 32M -minrate 32M -maxrate 32M -bufsize 32M -x264-params nal-hrd=cbr -pix_fmt yuv420p "$RUNNER_TEMP/signal-large.mp4"
stat -c '%s' "$RUNNER_TEMP/signal-large.mp4" > dual-evidence/video-size.txt
adb -s emulator-5554 push "$RUNNER_TEMP/signal-large.mp4" /sdcard/Download/signal-large.mp4
phase A publish-video
adb -s emulator-5554 shell am force-stop com.wb.signalpro
phase A resume-video
phase B partial-video
adb -s emulator-5556 shell am force-stop com.wb.signalpro
phase B receive-video
phase A publish
phase B receive
phase B publish
phase A receive
# Genuine target process termination, then disconnected network path and persistent recovery.
for role in A B; do
  serial=emulator-5554; [[ "$role" == B ]] && serial=emulator-5556
  adb -s "$serial" shell am force-stop com.wb.signalpro
  adb -s "$serial" reverse --remove tcp:8765
  phase "$role" offline
  adb -s "$serial" reverse tcp:8765 tcp:8765
  phase "$role" receive
  adb -s "$serial" pull "/sdcard/Pictures/dual-$role.png" "dual-evidence/$role.png"
  adb -s "$serial" logcat -d > "dual-evidence/$role-logcat.txt"
  phase "$role" migration
done
