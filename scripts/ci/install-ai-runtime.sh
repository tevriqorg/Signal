#!/usr/bin/env bash
set -euo pipefail
# Refresh only the signed Ubuntu package sources needed by OCR. Unrelated runner
# vendor repositories must not make product verification depend on their uptime.
if [[ -f /etc/apt/sources.list.d/ubuntu.sources ]]; then
  apt_options=(-o Dir::Etc::sourcelist=sources.list.d/ubuntu.sources -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0)
else
  apt_options=(-o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0)
fi
sudo apt-get "${apt_options[@]}" update -qq
sudo apt-get "${apt_options[@]}" install -y tesseract-ocr tesseract-ocr-jpn tesseract-ocr-chi-sim fonts-dejavu-core
python -m pip install --only-binary=:all: torch torchvision --index-url https://download.pytorch.org/whl/cpu
python -m pip install --only-binary=:all: -r server/requirements-ai.txt
