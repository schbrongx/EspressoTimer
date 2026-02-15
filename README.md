# EspressoTimer

EspressoTimer is an Android app for manual espresso shot timing.

This repository includes a **Training / Learning Mode** for collecting labeled audio data and computing a per-profile learned trigger artifact.
Runtime auto-start/inference in the timer flow is still out of scope.

## Training / Learning Mode

Open the app and enter **Training / Learning Mode**.

### Profile lifecycle

Per profile you can:

- Create
- List
- Select active
- Rename
- Delete
- Reset training data

Reset/delete remove both captured samples and learned artifacts.

### Capture and readiness

Training session behavior:

- Microphone starts when the session opens (permission required)
- Audio is buffered continuously in a rolling buffer (~10s)
- **SHOT START** stores a positive sample (`1.5s` pre-roll + `2.5s` post-roll)
- Negative/background samples (`2.0s`) are auto-captured away from positive windows
- Optional manual negative capture via **Add Background Sample Now**

Minimums for learned-trigger readiness:

- Positives: `>= 20`
- Negatives: `>= 40`

### Profile status model

Each profile shows one status badge:

- `NOT READY`: below minimum sample counts
- `READY`: minimums met, no learned artifact yet
- `LEARNED`: learned artifact exists and matches current dataset revision/hash
- `OUTDATED`: learned artifact exists but dataset changed since last compute

UI primary action follows status:

- `NOT READY`: Start/Continue Training
- `READY`: Compute Learned Trigger
- `OUTDATED`: Recompute Learned Trigger
- `LEARNED`: Up-to-date state shown (no compute needed)

### Compute Learned Trigger output

Compute/Recompute runs a deterministic per-profile pipeline:

- Loads all positive/negative WAV files
- Extracts fixed log-mel features
- Deterministically creates train/validation split (stratified 80/20 with deterministic seed)
- Computes normalization stats from train split
- Trains compact logistic regression classifier
- Evaluates validation metrics (accuracy, precision, recall, F1)
- Selects threshold by best validation F1 with deterministic tie-breakers

Artifacts are written under `learned/`:

- `model.json`: feature settings, normalization, model parameters, threshold, dataset revision/hash
- `report.json`: sample counts, split method, metrics, threshold rationale, quality label (`Good`/`OK`/`Weak`)
- `learned_at.txt`: ISO8601 timestamp

If minimums are not met, compute is blocked with an actionable reason.

## Audio backend dependency

This Android implementation uses `AudioRecord` (platform microphone API).

If microphone permission/device is unavailable, Training Mode stays open and shows a clear error/retry state instead of crashing.

## Local storage

Training data is saved in app-private storage:

`/data/user/0/com.schbrongx.espressotimer/files/data/`

Layout:

```text
data/
  profiles.json
  training/<profile_id>/
    positives/
      <ISO8601>_pos_*.wav
      <ISO8601>_pos_*.json
    negatives/
      <ISO8601>_neg_*.wav
      <ISO8601>_neg_*.json
    events.jsonl
    learned/
      model.json
      report.json
      learned_at.txt
```

`events.jsonl` is append-only. Legacy `index.jsonl` is migrated to `events.jsonl` when profile layout is ensured.

All audio and learned artifacts stay local on-device. No upload is performed.
