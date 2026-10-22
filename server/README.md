# Signal Server — Android-first independent photo library (#14)

The server independently stores photo/video metadata, device provenance, JPEG previews,
verified full originals, and a resumable upload ledger. It does not access AIBOX, P14,
or any private project. No user data or credentials belong in this repository.

## Run in an isolated development environment

```sh
export SIGNAL_ENROLL_TOKEN='replace-with-a-long-random-secret'
export SIGNAL_DATA_DIR='/private/location/for/persistent/photos'
export SIGNAL_BIND='127.0.0.1'
python3 server/signal_server.py
```

`/health` describes the supported protocol. Use an HTTPS reverse proxy and private
authentication when exposing beyond a local integration harness. Server traffic over
plain HTTP is **not** an acceptable deployment configuration.

Registration uses a user-entered enrollment secret and gives each device its own token.
The mobile client must store that device token with Android Keystore. For a production
environment, provisioning should additionally support revocation and rate limits.

API: `POST /v1/devices`, `POST /v1/media`,
`PUT /v1/media/{id}/thumbnail`, `POST /v1/uploads`,
`PUT /v1/uploads/{id}`, `GET /v1/media?since=...`,
`GET /v1/media/{id}/thumbnail`, `GET /v1/media/{id}/original`,
`GET /v1/search?q=...`. All /v1 requests other than enrollment require the
per-device bearer credential.

- Committed originals are content-addressed by SHA-256 and never expire automatically.
- Metadata and previews are first-class: videos may wait for fast network access.
- Repeated registration of the same local media ID is idempotent.
- Chunk offsets and hashes are checked; interrupted uploads resume.
- Search currently supports **metadata / filename / supplied search text only**.
  The health endpoint explicitly reports that AI vision is *not* implemented.
- Private setup secrets, storage files, personal photos and phone identifiers are
  never checked into Git.
- Automated tests create two synthetic devices and payloads under a temporary folder,
  cover authorization, thumbnail reads, duplicate handling, interrupted upload recovery,
  wrong hash rollback and multi-device catalog/search.

```sh
python3 -m unittest discover -s server -p 'test_*.py' -v
```

## Known hardening gaps

This is an isolated integration/server implementation, **not production approved**.
Production readiness needs enrollment secret rotation / per-device revocation,
rate limits and reverse-proxy upload configuration, quota alarms and disk snapshots,
a migration/backup plan, and hardening for large concurrent streaming responses.
Do not expose the raw stdlib HTTP server publicly.
