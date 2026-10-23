# G2 Candidate Grouping checkpoint

Issue: `tevriqorg/Signal#12`

G2 implements only deterministic Stage-A grouping for Semantic Gallery.

## Inputs

The segmenter consumes the already ordered `MediaItem` list from the G1 local index. It does not mutate, reorder, delete, or overwrite MediaStore truth.

## Boundaries

Candidate boundaries currently use:

- hard time gap: 90 minutes;
- screenshot ↔ camera transition when separated by at least 10 minutes;
- relative-path / bucket transition when separated by at least 30 minutes.

The thresholds are intentionally explicit and deterministic. They are candidates, not semantic truth, and can be recomputed later.

## Output

`CandidateGroup` includes:

- deterministic ID derived from first/last media identity and count;
- ordered MediaItems;
- time range;
- boundary reason.

No AI title, place, summary, OCR, entity extraction, or merge/split interpretation is produced in G2.

## Gallery UI

Gallery adds a section view that shows candidate event headers and preserves a one-tap raw timeline mode. The section UI is deliberately simple so later semantic interpretation can decorate the same boundary without replacing the native lazy-grid foundation.

## Acceptance

The Android 35 gate covers deterministic output, time-gap splitting, screenshot runs/transitions, bucket transitions, G1 MediaStore indexing/reconcile, and release APK build.
