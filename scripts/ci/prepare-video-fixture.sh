#!/usr/bin/env bash
set -euo pipefail
# Install/generate synthetic video before booting either emulator. The hosted
# runner's Azure mirror was observed taking >20 minutes for FFmpeg dependencies.
if ! command -v ffmpeg >/dev/null; then
  . /etc/os-release
  [[ "$ID" == ubuntu ]]
  sources=$(mktemp "$RUNNER_TEMP/signal-video-apt.XXXXXX.list")
  trap 'rm -f "$sources"' EXIT
  for suite in "$VERSION_CODENAME" "$VERSION_CODENAME-updates"; do
    printf 'deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://archive.ubuntu.com/ubuntu %s main universe\n' "$suite" >> "$sources"
  done
  printf 'deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://security.ubuntu.com/ubuntu %s-security main universe\n' "$VERSION_CODENAME" >> "$sources"
  apt_options=(-o "Dir::Etc::sourcelist=$sources" -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0 -o Acquire::Retries=2 -o Acquire::https::Timeout=20)
  sudo timeout --kill-after=15s 180s apt-get "${apt_options[@]}" update -qq
  sudo timeout --kill-after=15s 300s apt-get "${apt_options[@]}" install -y --no-install-recommends ffmpeg
fi
mkdir -p dual-evidence
echo '[fixture] generate valid large H264 video before emulator boot'
timeout --kill-after=10s 180s ffmpeg -nostdin -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' -t 36 -c:v libx264 -preset veryfast -profile:v high -level:v 4.1 -b:v 32M -minrate 32M -maxrate 32M -bufsize 32M -x264-params nal-hrd=cbr -pix_fmt yuv420p "$RUNNER_TEMP/signal-large.mp4"
stat -c '%s' "$RUNNER_TEMP/signal-large.mp4" > dual-evidence/video-size.txt
sha256sum "$RUNNER_TEMP/signal-large.mp4" > dual-evidence/video-fixture-sha256.txt
