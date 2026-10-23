#!/usr/bin/env bash
set -euo pipefail
# Public hosted runners only. Keep retries separate from product assertions.
# These packages match the pinned emulator action's SDK installer.
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
image="$ANDROID_HOME/system-images/android-35/google_apis/x86_64"
# Return sdkmanager's result, rather than yes's expected SIGPIPE.
timeout --kill-after=15s 60s bash -c 'set +o pipefail; yes | "$1" --licenses' _ "$sdkmanager"
for attempt in 1 2 3; do
  echo "[emulator-sdk] installation attempt $attempt/3 (bounded to 8 minutes)"
  if timeout --kill-after=15s 8m "$sdkmanager" --install \
      'build-tools;37.0.0' platform-tools 'platforms;android-35' emulator \
      'system-images;android-35;google_apis;x86_64' --channel=0; then
    if [[ -s "$image/package.xml" && -s "$image/system.img" && \
          -x "$ANDROID_HOME/emulator/emulator" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
      echo '[emulator-sdk] required image and tools installed'
      exit 0
    fi
  fi
  echo '[emulator-sdk] attempt failed; no tests have started'
done
echo 'FAIL: emulator SDK preparation did not complete successfully'
exit 1
