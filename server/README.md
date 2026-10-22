# Signal Server — Android enhanced gallery (#14)

Signal Server is the single center for the gallery catalog, provenance, previews,
SHA-256 verified complete originals, resumable upload ledger and searchable AI index.
It has no AIBOX, P14 or Hermes runtime dependency. Only generated media belongs in tests.
This PR implements Android; the wider Issue's iOS work remains outside this takeover.

## Isolated runtime

```sh
export SIGNAL_ENROLL_TOKEN='replace-with-a-long-random-secret'
export SIGNAL_DATA_DIR='/private/location/for/persistent/photos'
export SIGNAL_BIND='127.0.0.1'
python3 server/signal_server.py
```

The HTTP listener is for loopback integration and a trusted HTTPS reverse proxy.
Never expose the raw stdlib listener publicly. Android release clients require HTTPS;
loopback HTTP is a debug-only exception. TLS hostname/CA verification is not disabled.
The enrollment secret creates a single personal library, with distinct per-device bearer
tokens protected by Android Keystore. This is not a multi-user shared-album service.
Use the separate admin key for device revocation. Rotation replaces the current device
credential; keep enrollment/admin secrets out of URLs, logs and source control.

API: `POST /v1/devices`, `POST /v1/media`,
`PUT /v1/media/{id}/thumbnail`, `POST /v1/uploads`, `PUT /v1/uploads/{id}`,
`GET /v1/media?since=...`, `GET /v1/media/{id}/thumbnail`,
`GET /v1/media/{id}/original` (Range supported), `GET /v1/search?q=...`,
`GET /v1/status`, `POST /v1/analysis/retry`, `POST /v1/devices/rotate`,
`DELETE /v1/devices/{id}` (admin key) and `DELETE /v1/media/{id}` (owner tombstone).
All `/v1` routes except enrollment require a device bearer token.

- Media identity is stable per originating device/local ID; changing source fingerprints
  invalidates old proofs, previews and pending upload sessions without deleting old bytes.
- Upload sessions bind owner, source revision, byte count, offset and SHA-256. Files are
  fsynced before commit. Existing bytes are verified before deduplication or serving;
  corruption becomes a repairable status and cannot certify a bad original.
- Catalog page cursors and current asset revisions are distinct. Client replay cannot
  roll back a newer state. Tombstones never automatically remove stored originals.
- `SIGNAL_QUOTA_BYTES` bounds stored/reserved bytes. Disk headroom, bounded HTTP threads,
  socket deadlines and per-IP admission limits protect the isolated service. Configure
  reverse-proxy request/body/time limits as part of a separately approved deployment.
- `MediaService.backup(destination)` creates a consistent SQLite/files snapshot and SHA
  manifest; stop the service first for operational backups. Restore into a separate empty
  location using `MediaService.restore`. Damaged originals prevent a valid manifest.
  No automatic expiry, original cleanup or production cutover is performed.

## Actual local AI and OCR

Install CPU binary wheels (`server/requirements-ai.txt`, plus CPU torch/torchvision)
into an isolated virtualenv and Tesseract with English, Japanese and Simplified Chinese
language data. Enable `SIGNAL_AI_ENABLED=1`. Models default to SentenceTransformers
`clip-ViT-B-32` and `clip-ViT-B-32-multilingual-v1`; `SIGNAL_IMAGE_MODEL` and
`SIGNAL_TEXT_MODEL` may point to fully downloaded local snapshots. For an offline runtime,
set `HF_HUB_OFFLINE=1` and `TRANSFORMERS_OFFLINE=1`. No remote inference API is used.
`SIGNAL_AI_INT8=1` quantizes linear layers sequentially to reduce runtime memory; both
float and int8 paths have dedicated real-model CI.

Indexing persists original hashes, actual OCR, EXIF context and visual vectors. Search
combines multilingual visual similarity, OCR/filename/context, dates (Asia/Tokyo), and
EXIF GPS proximity. Responses carry match reasons and similarity/context excerpts.
Unknown place names require available metadata/context; there is no external geocoder.
Unreadable media enters bounded retry/error state without blocking unrelated assets.
Authenticated status distinguishes deferred indexing, failed analysis and storage faults.

## Verification and limits

```sh
python3 -m unittest discover -s server -p 'test_*.py' -v
SIGNAL_TEST_AI=1 python3 -m unittest discover -s server -p test_search_index.py -v
```

The first command skips actual-model tests unless explicitly enabled. `search.yml` runs
real OCR and English/Chinese retrieval with generated media. `android.yml` runs two
separate writable Android 35 emulators, actual APK/MediaStore transfers, ADB interruptions,
UI Automator, video decode/playback, permission transitions, migrations and OS workers.
Server-only synthetic device tests do not substitute for those Android tests.
See `docs/acceptance/ISSUE14_ANDROID_MATRIX.md` for current acceptance status and evidence.
Production approval, physical-device/OEM validation, public endpoint cutover and iOS are
separate; none can be inferred from an emulator or loopback server pass.
