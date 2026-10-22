#!/usr/bin/env bash
set -euo pipefail
export SIGNAL_ENROLL_TOKEN=synthetic-test-enrollment-secret
export SIGNAL_DATA_DIR="$RUNNER_TEMP/signal-test-media"
export SIGNAL_BIND=127.0.0.1
python3 server/signal_server.py > signal-server.log 2>&1 &
srv=$!
trap 'kill "$srv" 2>/dev/null || true' EXIT
adb reverse tcp:8765 tcp:8765
adb shell settings put global stay_on_while_plugged_in 7
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
set +e
gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.wb.signalpro.photos.DualEmulatorInstrumentedTest --stacktrace 2>&1 | tee gradle-instrumentation.log
status=${PIPESTATUS[0]}
adb pull /sdcard/Android/data/com.wb.signalpro/files/signal-gallery-emulator.png gallery-screenshot.png || true
adb pull /sdcard/Android/data/com.wb.signalpro/files/gallery-window.xml gallery-window.xml || true
adb logcat -d > gallery-logcat.txt
exit "$status"
