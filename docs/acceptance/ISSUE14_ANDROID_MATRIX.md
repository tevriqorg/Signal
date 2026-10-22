# Issue 14 Android independent acceptance

Authority: Signal #14; Android-only takeover authorized 2026-10-22. PR #15.
Status: NOT ACCEPTED. Previous 19-test run is not dual-emulator evidence.

| Requirement / audit finding | Code | Required independent evidence | Status |
|---|---|---|---|
| Source revision invalidates original and stale uploads | server/signal_server.py; GallerySyncEngine; GallerySyncStore | HTTP regression; Android MediaStore edit and migration | Server regression passed; Android pending |
| Recoverable original download | SignalMediaClient.fetchOriginal | interruption and force-stop on two emulators; hash check | Implemented, pending |
| Catalog paging stops on empty page | GallerySyncEngine | request count and >200 assets | Implemented, pending |
| Mixed timeline, source and three-state dots | GalleryActivity; GalleryTransferBadge | dual-emulator UI Automator and screenshots | Pending |
| Foreground and OS-scheduled catchup | GalleryBackgroundWorker | offline, Doze, process death, resume | Pending |
| Full library beyond 2000 entries and accurate pending totals | MediaIndexCoordinator; GallerySyncEngine | synthetic large catalog | Pending |
| Video preview, playback and opportunity upload | GalleryActivity; SignalMediaClient | valid large MP4 on A and B, interruption and playback | Pending |
| Foreground invitation notification | GalleryBackgroundWorker | notification permission and throttling | Pending |
| OCR and visual natural-language search with evidence | Signal Server | generated fixtures, actual OCR/visual index, explainable matches | Missing |
| Remote failure status and healthy cloud | GalleryActivity | original/preview failure, retry, restart | Pending |
| Cache correctness and limits | SignalMediaClient | revision invalidation, corruption, low disk | Pending |
| Server security and recovery | Signal Server | revocation, quotas, backup/restore, isolated HTTPS | Pending |
| Full/partial/denied permission; SQLite upgrade | Android media and sync stores | instrumented system permission and v1 to v2 tests | Pending |
| Actual A to B to A transfer | two Android emulators | separate serials, APKs, MediaStore, ADB/UI Automator, SHA evidence | Missing |
| Target server isolated acceptance | private companion | synthetic-only isolated runtime; HTTPS and persistence | Pending |
| Review and merge | PR #15 | all required CI; independent review | Blocked on acceptance |

No iOS development, AIBOX integration, Hermes, private data, or production cutover.
No issue closure or completion claim until all Android acceptance rows pass.
