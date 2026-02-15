# EspressoTimer

EspressoTimer is an Android app for manual espresso shot timing.

This repository includes a **Training / Learning Mode** for collecting labeled audio data for future shot-start recognition.
Current scope is data capture only (no ML inference or auto-start logic yet).

## Training / Learning Mode

Open the app and enter **Training / Learning Mode**.

### Profile CRUD

- Create profile
- List profiles
- Select active profile
- Rename profile
- Delete profile
- Reset training data for a profile

Each profile shows:

- Positive and negative sample counts
- Total recorded seconds
- Last event timestamp

### Training lifecycle

- Start training (new profile)
- Continue training (profile with existing data)
- Reset training data (clear positives, negatives, and index)

### Training session behavior

- Microphone starts immediately when session opens (permission required)
- Audio is recorded continuously into a rolling ~10s buffer
- Large **SHOT START** button labels a positive event
- Positive window saved as: `1.5s` pre-roll + `2.5s` post-roll
- Negative/background windows (`2.0s`) are captured automatically from regions at least `3.0s` away from shot events
- Optional manual negative capture via **Add Background Sample Now**
- Session can be stopped safely anytime without data loss

UI summary includes:

- Positives, negatives, session positives, pending shot events
- Total recorded seconds
- Last event timestamp
- Buffered audio seconds
- Guidance text: record at least 20 shot starts

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
    index.jsonl
```

All audio stays local on device. No upload is performed.

