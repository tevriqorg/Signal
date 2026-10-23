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
# 仅隔离临时 AVD 的无关 Launcher ANR；Signal 与系统权限/错误对话框仍可见。
if adb shell pm path com.google.android.apps.nexuslauncher | grep -q '^package:'; then
  adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
fi
set +e
gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.wb.signalpro.photos.DualEmulatorInstrumentedTest --stacktrace 2>&1 | tee gradle-instrumentation.log
status=${PIPESTATUS[0]}
adb pull /data/local/tmp/signal-gallery-emulator.png gallery-screenshot.png || true
adb pull /data/local/tmp/gallery-window.xml gallery-window.xml || true
adb logcat -d > gallery-logcat.txt
exit "$status"
