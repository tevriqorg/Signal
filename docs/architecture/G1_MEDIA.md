# G1 Media index + native Gallery checkpoint

Issue: `tevriqorg/Signal#5`

G1 implements the Media POC without entering Semantic Gallery (G2).

## Runtime path

```text
Android media permission
  → MediaPermissionPolicy (FULL / PARTIAL / DENIED)
  → MediaStoreSource
  → version + generation reconcile policy
  → SQLite media index + per-volume durable checkpoint
  → GalleryActivity
```

The index stores only authorized MediaStore metadata and content URIs. Original media remains owned by MediaStore and is not copied into Signal.

## Reconcile rules

- first run: full authorized snapshot;
- Android 11+: same MediaStore version + higher generation: incremental query;
- MediaStore version change / generation rollback: full reconcile;
- full ↔ partial permission change: full reconcile against the newly authorized set;
- Android 10: conservative full reconcile;
- process restart: checkpoint is loaded from SQLite and the current generation is compared before deciding the next scan.

A ContentObserver triggers lightweight reconcile while the Gallery process is alive. Resume always reconciles again, so process death does not make the observer a single point of failure.

## Privacy / degraded state

Android 14+ selected-photo access is explicitly represented as `PARTIAL`. The Gallery UI says that only part of the library is visible and never labels it as a complete library.

No raw photo/video is uploaded. G1 has no Server sync and no AI interpretation.

## CI acceptance

- JVM tests cover version/generation/permission transition decisions;
- Android 35 emulator test creates a real MediaStore image, performs a full index, recreates the coordinator (process-level persistence boundary), adds another image, and verifies catch-up;
- release APK continues to build in GitHub Actions.

Semantic grouping, OCR, Episode titles and AI interpretation are G2+ and intentionally absent here.
