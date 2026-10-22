#!/usr/bin/env bash
set -euo pipefail
mkdir -p dual-evidence
# APKs are built by a separate hosted-runner step before either emulator boots.
echo '[acceptance] verify prebuilt APKs'
[[ -s app/build/outputs/apk/debug/app-debug.apk ]]
[[ -s app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ]]
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > dual-evidence/apk-sha256.txt
srv=""
profile=runner-loopback
phase_timeout=180s
fault_attempts=120
if [[ "${SIGNAL_ISOLATED_TARGET:-false}" == true ]]; then
  [[ "${SIGNAL_TEST_ENDPOINT:-}" == https://* ]] || { echo 'FAIL: isolated target requires HTTPS'; exit 1; }
  [[ "${#SIGNAL_TEST_ENROLLMENT}" -ge 16 ]] || { echo 'FAIL: missing isolated enrollment credential'; exit 1; }
  profile=isolated-target-https
  phase_timeout=900s
  fault_attempts=1200
else
  export SIGNAL_TEST_ENDPOINT=http://127.0.0.1:8765
  export SIGNAL_TEST_ENROLLMENT=synthetic-test-enrollment-secret
  export SIGNAL_ENROLL_TOKEN="$SIGNAL_TEST_ENROLLMENT"
  export SIGNAL_DATA_DIR="$RUNNER_TEMP/signal-dual-data"
  export SIGNAL_BIND=127.0.0.1
  export SIGNAL_AI_ENABLED=1
  export SIGNAL_AI_INT8=1
  python3 server/signal_server.py > dual-evidence/server.log 2>&1 &
  srv=$!
fi
printf '{"server_profile":"%s","tls_verification":"platform defaults; no bypass","fixture_run":"%s"}\n' "$profile" "${GITHUB_RUN_ID:-local}" > dual-evidence/profile.json
trap '[[ -z "$srv" ]] || kill "$srv" 2>/dev/null || true; adb -s emulator-5556 emu kill >/dev/null 2>&1 || true' EXIT
echo "[acceptance] server profile: $profile; create second independent AVD"
# Reuse the installed system image, create a distinct AVD with its own writable userdata.
avdmanager create avd --force --name signal-peer --package 'system-images;android-35;google_apis;x86_64' --device pixel_6 <<< no
"$ANDROID_HOME/emulator/emulator" -avd signal-peer -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 1536 > dual-evidence/emulator-B.log 2>&1 &
for serial in emulator-5554 emulator-5556; do
  echo "[acceptance] wait for $serial boot and install actual APKs"
  timeout 180s adb -s "$serial" wait-for-device
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
  # Pixel Launcher 曾在 CI 中 ANR 并遮住已正常显示的相册。仅禁用这个
  # 无关的临时 AVD 包，不隐藏 Signal ANR、系统权限或其他错误对话框。
  if adb -s "$serial" shell pm path com.google.android.apps.nexuslauncher | grep -q '^package:'; then
    adb -s "$serial" shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
  fi
  adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb -s "$serial" reverse tcp:8765 tcp:8765
  adb -s "$serial" shell pm grant com.wb.signalpro android.permission.READ_MEDIA_IMAGES
  adb -s "$serial" shell pm grant com.wb.signalpro android.permission.READ_MEDIA_VIDEO
  adb -s "$serial" shell getprop > "dual-evidence/$serial-properties.txt"
done
adb devices -l > dual-evidence/devices.txt
[[ $(adb devices | grep -c '^emulator-.*device$') -eq 2 ]]
echo '[acceptance] both serials present; verify bounded AI-enabled health'
python3 scripts/ci/gallery-health-check.py --attempts 6 --output dual-evidence/health.json
# ADB remains the control channel; actual application networking is disabled on BOTH
# profiles. Removing the reverse alone cannot interrupt a direct public HTTPS session.
network_off() {
  local serial="$1"
  adb -s "$serial" reverse --remove tcp:8765
  adb -s "$serial" shell svc data disable
  adb -s "$serial" shell svc wifi disable
  for attempt in $(seq 1 30); do
    adb -s "$serial" shell dumpsys connectivity > "dual-evidence/$serial-network-off.txt"
    grep -q 'Active default network: none' "dual-evidence/$serial-network-off.txt" && return 0
    sleep 1
  done
  echo 'FAIL: application network was not disconnected'
  return 1
}
network_on() {
  local serial="$1"
  adb -s "$serial" reverse tcp:8765 tcp:8765
  adb -s "$serial" shell svc wifi enable
  adb -s "$serial" shell svc data enable
  for attempt in $(seq 1 90); do
    adb -s "$serial" shell dumpsys connectivity > "dual-evidence/$serial-network-on.txt"
    if python3 scripts/ci/verified-emulator-wifi.py "dual-evidence/$serial-network-on.txt"; then return 0; fi
    sleep 1
  done
  echo 'FAIL: validated unmetered Wi-Fi did not recover'
  return 1
}
phase_counter=0
phase() {
  local role="$1" task="$2" serial=emulator-5554
  phase_counter=$((phase_counter + 1))
  local evidence_name="$phase_counter-$role-$task"
  echo "[acceptance] phase $evidence_name"
  [[ "$role" == B ]] && serial=emulator-5556
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
  adb -s "$serial" shell wm dismiss-keyguard
  timeout "$phase_timeout" adb -s "$serial" shell am instrument -w -r -e class com.wb.signalpro.photos.DualEmulatorInstrumentedTest -e role "$role" -e phase "$task" -e endpoint "$SIGNAL_TEST_ENDPOINT" -e enrollment "$SIGNAL_TEST_ENROLLMENT" com.wb.signalpro.test/androidx.test.runner.AndroidJUnitRunner | tee "dual-evidence/$evidence_name.txt"
  adb -s "$serial" logcat -d > "dual-evidence/$evidence_name-logcat.txt"
  adb -s "$serial" exec-out screencap -p > "dual-evidence/$evidence_name.png"
  grep -q 'OK (1 test)' "dual-evidence/$evidence_name.txt"
  ! grep -qE 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "dual-evidence/$evidence_name.txt"
}
interrupt_transfer() {
  local role="$1" task="$2" serial=emulator-5554
  echo "[acceptance] transfer fault $role $task: wait for confirmed in-flight bytes"
  [[ "$role" == B ]] && serial=emulator-5556
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
  adb -s "$serial" shell wm dismiss-keyguard
  timeout "$phase_timeout" adb -s "$serial" shell am instrument -w -r -e class com.wb.signalpro.photos.DualEmulatorInstrumentedTest -e role "$role" -e phase "$task" -e endpoint "$SIGNAL_TEST_ENDPOINT" -e enrollment "$SIGNAL_TEST_ENROLLMENT" com.wb.signalpro.test/androidx.test.runner.AndroidJUnitRunner > "dual-evidence/$role-$task.txt" 2>&1 &
  local process=$! progress=0
  for attempt in $(seq 1 "$fault_attempts"); do
    progress=$(adb -s "$serial" shell run-as com.wb.signalpro cat "files/dual-$task.progress" 2>/dev/null | tr -d '\r' || true)
    [[ "$progress" =~ ^[0-9]+$ ]] && [[ "$progress" -ge 1048576 ]] && break
    sleep 0.5
  done
  if ! [[ "$progress" =~ ^[0-9]+$ ]] || [[ "$progress" -lt 1048576 ]]; then
    cat "dual-evidence/$role-$task.txt"
    echo "FAIL: no confirmed in-flight bytes; fault injection was not performed"
    adb -s "$serial" logcat -d > "dual-evidence/$role-$task-failure-logcat.txt"
    wait "$process" || true
    return 1
  fi
  if ! kill -0 "$process" 2>/dev/null; then
    echo "FAIL: transfer already ended before interruption"
    return 1
  fi
  printf '%s %s bytes=%s: disconnect actual network then force-stop real APK\n' "$role" "$task" "$progress" | tee "dual-evidence/$role-$task-interruption.txt"
  network_off "$serial"
  sleep 1
  adb -s "$serial" shell am force-stop com.wb.signalpro
  wait "$process" || true
  network_on "$serial"
}
command -v ffmpeg >/dev/null || { sudo apt-get update -qq; sudo apt-get install -y ffmpeg; }
timeout 180s ffmpeg -nostdin -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' -t 36 -c:v libx264 -preset veryfast -profile:v high -level:v 4.1 -b:v 32M -minrate 32M -maxrate 32M -bufsize 32M -x264-params nal-hrd=cbr -pix_fmt yuv420p "$RUNNER_TEMP/signal-large.mp4"
stat -c '%s' "$RUNNER_TEMP/signal-large.mp4" > dual-evidence/video-size.txt
adb -s emulator-5554 push "$RUNNER_TEMP/signal-large.mp4" /sdcard/Download/signal-large.mp4
phase A publish-video
interrupt_transfer A interrupt-upload
phase A resume-video
interrupt_transfer B interrupt-download
phase B receive-video
phase A publish
phase B receive
phase B publish
phase A receive
# Genuine target process termination, then disconnected network path and persistent recovery.
for role in A B; do
  serial=emulator-5554; [[ "$role" == B ]] && serial=emulator-5556
  adb -s "$serial" shell am force-stop com.wb.signalpro
  network_off "$serial"
  phase "$role" offline
  network_on "$serial"
  phase "$role" receive
  adb -s "$serial" pull "/sdcard/Android/data/com.wb.signalpro/files/dual-$role.png" "dual-evidence/$role.png"
  adb -s "$serial" logcat -d > "dual-evidence/$role-logcat.txt"
  phase "$role" migration
done

# Actual Android permission changes occur between instrumentation processes.
for role in A B; do
  serial=emulator-5554; [[ "$role" == B ]] && serial=emulator-5556
  phase "$role" permission-full
  adb -s "$serial" shell pm revoke com.wb.signalpro android.permission.READ_MEDIA_VIDEO
  adb -s "$serial" shell pm grant com.wb.signalpro android.permission.READ_MEDIA_VISUAL_USER_SELECTED
  phase "$role" permission-partial
  adb -s "$serial" shell pm revoke com.wb.signalpro android.permission.READ_MEDIA_IMAGES
  adb -s "$serial" shell pm revoke com.wb.signalpro android.permission.READ_MEDIA_VISUAL_USER_SELECTED
  phase "$role" permission-denied
  adb -s "$serial" shell pm grant com.wb.signalpro android.permission.READ_MEDIA_IMAGES
  adb -s "$serial" shell pm grant com.wb.signalpro android.permission.READ_MEDIA_VIDEO
  adb -s "$serial" shell dumpsys jobscheduler > "dual-evidence/$role-jobscheduler.txt"
done

# Exercise actual WorkManager/JobScheduler, including Doze deferral and later OS execution.
for role in A B; do
  serial=emulator-5554; [[ "$role" == B ]] && serial=emulator-5556
  phase "$role" background-seed
  adb -s "$serial" shell input keyevent KEYCODE_SLEEP
  adb -s "$serial" shell dumpsys battery unplug
  adb -s "$serial" shell dumpsys deviceidle force-idle
  adb -s "$serial" shell dumpsys deviceidle get deep | tee "dual-evidence/$role-idle-state.txt"
  grep -q IDLE "dual-evidence/$role-idle-state.txt"
  adb -s "$serial" shell am kill com.wb.signalpro
  adb -s "$serial" shell dumpsys deviceidle unforce
  adb -s "$serial" shell dumpsys battery reset
  adb -s "$serial" shell dumpsys jobscheduler > "dual-evidence/$role-background-jobs.txt"
  for job in $(adb -s "$serial" shell dumpsys jobscheduler | sed -n 's/.*JOB #[^/]*\/\([0-9][0-9]*\):.*com.wb.signalpro\/.*/\1/p' | tr -d '\r' | sort -u); do
    adb -s "$serial" shell cmd jobscheduler run -f com.wb.signalpro "$job"
  done
  phase "$role" background-verify
done

# Reproduce the original audit defect on a real MediaStore row while offline,
# then verify a changed original AND cover arrive at the second Android device.
network_off emulator-5554
phase A edit-offline
network_on emulator-5554
phase A recover-edited
phase B receive-edited
adb -s emulator-5556 pull /sdcard/Android/data/com.wb.signalpro/files/dual-edited-B.png dual-evidence/edited-B.png

# User-only local cleanup must leave the central original browsable on its origin device.
phase A archive-local
adb -s emulator-5554 pull /sdcard/Android/data/com.wb.signalpro/files/dual-archive-A.png dual-evidence/archive-A.png
