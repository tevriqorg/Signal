# Third-party notices

## iPerf3 Android binaries

Signal bundles Android iPerf3 command-line binaries from `davidBar-On/android-iperf3`, version 3.22, for arm64-v8a, armeabi-v7a and x86_64.

- Binary source: https://github.com/davidBar-On/android-iperf3
- Upstream iPerf3: https://github.com/esnet/iperf
- `android-iperf3` repository license: MIT
- iPerf3 upstream license: BSD 3-Clause

The bundled executable is invoked locally by Signal for user-initiated, single-server bandwidth tests. Signal does not run an iPerf3 server.

## Public iPerf3 server directory

The initial public server presets are based on the community-maintained `R0GGER/public-iperf3-servers` directory. Public endpoints are not operated by Signal and may change or become unavailable.

- Directory: https://github.com/R0GGER/public-iperf3-servers

## Optional server-side search runtime

The isolated AI runtime downloads dependencies and model snapshots separately; model
weights and private indexes are not bundled into the Android APK or this repository.

- SentenceTransformers: https://github.com/huggingface/sentence-transformers (Apache-2.0)
- Multilingual CLIP model: https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1 (Apache-2.0 model card)
- CLIP image model: https://huggingface.co/sentence-transformers/clip-ViT-B-32 ; upstream https://github.com/openai/CLIP (MIT)
- Tesseract and language data: https://github.com/tesseract-ocr/tesseract and https://github.com/tesseract-ocr/tessdata_fast (Apache-2.0)
- PyTorch: https://github.com/pytorch/pytorch (BSD-style); Pillow: https://github.com/python-pillow/Pillow (HPND)

Preserve upstream licenses/model cards when provisioning or redistributing the separate
runtime. Generated test images contain no user photographs or private location data.
