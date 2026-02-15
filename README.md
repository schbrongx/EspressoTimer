# EspressoTimer

EspressoTimer is an Android app for manual espresso shot timing.  
This repository now includes a full local **Training / Learning Mode** for building per-profile audio trigger artifacts for future auto-start detection.

## Training / Learning Mode

Open the app, then tap the training icon to enter **Training / Learning Mode**.

### 1. Manage profiles

- Create profile
- Rename profile
- Delete profile
- Select active profile
- Reset training data for a profile (clears captured samples and learned artifacts)

Each profile shows:

- Positive / negative sample counts
- Status badge: `NOT READY`, `READY`, `LEARNED`, `OUTDATED`
- Last learned timestamp and quality label (`Good`, `OK`, `Weak`) when available

### 2. Record training data

For a profile, choose **Start Training** or **Continue Training**.

During a session:

- Microphone starts listening immediately (permission required)
- Tap the large **SHOT START** button at espresso shot start
- Positive example window is captured from:
  - `1.5s` before tap
  - `2.5s` after tap
- Background negatives are captured automatically from windows at least `3.0s` away from any positive window
- You can also tap **Add background now**

Readiness thresholds:

- Positives: `>= 20`
- Negatives: `>= 40`

### 3. Compute learned trigger

When profile status is `READY`, run **Compute Learned Trigger**.  
If more data is added later, status becomes `OUTDATED` and action becomes **Recompute Learned Trigger**.

The compute pipeline:

- Extracts fixed log-mel features from WAV samples
- Trains a deterministic linear logistic regression model
- Normalizes features with saved mean/std
- Chooses threshold by best validation F1 (precision tie-break)
- Writes compact artifact + report locally

## Microphone dependency

This Android implementation uses the platform `AudioRecord` backend (not Python `sounddevice`).  
If mic permission is denied or mic init fails, Training Mode still opens and shows an error state with retry guidance.

## Local data layout

All training data is stored locally in app-private storage:

`/data/user/0/com.schbrongx.espressotimer/files/data/`

Structure:

```text
data/
  profiles.json
  training/<profile_id>/
    positives/
      *.wav
      *.json
    negatives/
      *.wav
      *.json
    events.jsonl
    learned/
      model.json
      report.json
      learned_at.txt
```

## Learned artifact output

`learned/model.json` contains:

- Feature extractor settings
- Normalization stats (`mean`, `std`)
- Linear model parameters (`weights`, `bias`)
- Decision threshold
- Dataset revision used for training

`learned/report.json` contains:

- Sample counts
- Train/validation split details
- Metrics (`accuracy`, `precision`, `recall`, `f1`)
- Recommended threshold and rationale
- Quality label (`Good`, `OK`, `Weak`)

These artifacts are prepared for future runtime audio-trigger integration.  
Auto-start timer runtime logic is intentionally not wired yet.

