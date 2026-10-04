# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repo

Hush is an offline, on-device lip-reading Android app (mouth a taught phrase → app acts on it). Phases 0–3 are done (Python lip-dot pipeline + DTW, and a working Kotlin app); Phase 4 (neural encoder) is half done. **`PHASES.md` → "Where things stand" lists what's half done and what's left.** Read these before changing anything:

- `ARCHITECTURE.md`: the pipeline, tech stack, datasets, rejected approaches, and the planned layout.
- `DECISION_LOG.md`: every decision with its reason (D-01…D-51). Later rows supersede earlier ones.
- `PHASES.md`: build order, dates, and "done when" tests. Prototype deadline: **2026-10-31**.

## Project conventions

- **Decision log is append-only.** Never edit an old entry. When something changes, add a new row (`D-NN`, date, decision, why, rejected) that says "Supersedes D-xx". Log any new stack, design, or scope decision there, and keep `ARCHITECTURE.md` consistent with it.
- **No empty scaffolding.** A folder (`training/`, `app/`, `data/`) is created only when its first real file is written.
- **Docker is paused** (D-29, D-32). Develop natively until the user says otherwise. The `Dockerfile` (targets `train` and `android`, with source mounted at `/hush`) stays in the repo unbuilt. Ask before pulling large images or datasets.
- `data/`, `*.npy`, and `*.mp4` are git-ignored. Datasets and cached landmarks live there.

## Architecture invariants (span multiple docs)

- **Input is landmarks, not pixels:** 40 MediaPipe Face Landmarker lip points per frame, centred on the mouth, scaled by mouth width, with head tilt removed. The *same* pinned model, `app/src/main/assets/face_landmarker.task` (D-39, one tracked copy read by both), and the *same* normalisation must be used in Python training (`training/extract.py`) and in Kotlin on the phone, or the model's inputs won't match. `training/golden_lips.json` (raw MediaPipe lip xy + image size in, expected dots out) must be reproduced by a Kotlin unit test. Dots are `(frames, 40, 2)` float32 with NaN rows where no face was found; left/right mouth corners always land on (∓0.5, 0).
- **Lip encoder:** a Keras 1D temporal CNN (~350k params) that outputs a 64-d fingerprint, exported as an int8 LiteRT `.tflite` under 1 MB. Training uses **TensorFlow/Keras on CPU**; PyTorch and litert-torch were rejected (D-31). No GRU/LSTM layers, because their int8 quantization is unreliable.
- **Personalisation never trains on the device.** To teach a phrase, the user mouths it 5–10 times and the cleaned dot sequences (not fingerprints, D-37) are saved to one JSON file in app-private storage (no DB). Fingerprints are recomputed on app start, so swapping the model never forces re-teaching. Matching is DTW on lip **movement** (each clip minus its average mouth shape, D-49), a phrase's distance = mean of its 2 nearest takes (D-47); the encoder only replaces it if it wins on live phone clips (D-50). Not sure (best ÷ second-best > a user-tunable ratio, D-46) → "Did you mean…" with the top 3; a confirmed clip becomes a new take (D-48).
- **DTW baseline comes first** (D-14). The neural encoder has to beat its accuracy to replace it.
- **No LLM in the core loop.** A deterministic router in `phrases.json` maps each phrase to an Android action. The cloud LLM is used only for opt-in, text-only "Ask AI". Video never leaves the phone.
- **Confirm rules (D-24):** messages, calls, and Emergency always need a tap or nod. Speak-aloud and timers skip confirm but show a one-tap stop/undo. The Confirm screen shows the 2nd and 3rd guesses as chips.
- **Latency:** camera, MediaPipe, and TTS are pre-warmed when the app opens. Landmarks are computed while the user mouths, and releasing push-to-talk ends the utterance. Actions use direct APIs (`SmsManager`, `ACTION_CALL`, `AlarmClock` skip-UI).
- **App stack:** Kotlin, CameraX, and Jetpack Compose (R8 + baseline profile), minSdk 24, target device a 2 GB RAM phone. Screens: Home, Confirm / "Did you mean…", Instant, Phrases, phrase page, Teach (2 steps), Settings, hidden Record mode (long-press "Hush") (D-51). Light theme with accent #1F6FEB, dark mode follows the system setting (dark accent #4C8DFF), red #C62828 only for Emergency, Atkinson Hyperlegible font, touch targets ≥ 48 px.

## Commands

Python (3.11 venv via uv; system Python is 3.14, which TensorFlow doesn't support):

```bash
uv venv --python 3.11 .venv && VIRTUAL_ENV=.venv uv pip install -r training/requirements.txt
.venv/bin/python training/test_extract.py                                   # self-check for the dot clean-up
.venv/bin/python training/extract.py data/miracl/dataset data/dots/miracl   # all clips → .npy (resumable, all cores)
.venv/bin/python training/extract.py --show <clip> out.png                  # draw dots over frames to eyeball them
.venv/bin/python training/extract.py --golden <clip> training/golden_lips.json  # reference for the Kotlin port
.venv/bin/python training/test_dtw.py                                       # self-check for DTW
.venv/bin/python training/dtw.py data/dots/miracl                            # DTW baseline: accuracy + rejection table (~6 s)
.venv/bin/python training/encoder.py train 0                                # encoder: GRID + 10 MIRACL speakers, scored on 5 unseen (~25 min)
.venv/bin/python training/encoder.py export                                 # int8 .tflite into app assets (written, not yet run)
```

Phone data: `adb exec-out run-as com.hush cat files/phrases.json` (taught + confirmed takes), `adb logcat -s Hush` (one line per attempt + what was confirmed).

Data is all git-ignored, and every download goes into its own folder here. Nothing is left in `~/Downloads`, and zips are deleted after unzipping. Only GRID, MIRACL-VC1 and own data are used (LRW and OuluVS2 are not, D-40/D-41).

```
data/
├── grid/                         # training/fetch_grid.sh (resumable): s1…s34 videos + alignments/
├── miracl/                       # Kaggle apoorvwatsky/miraclvc1: dataset/<F01..M08>/{words,phrases}/<01-10>/<rep 01-10>/color_NNN.jpg (+ depth_NNN.png), cropped/
├── own/<person>/                 # phone videos per training/RECORDING.md (deleted after dots extracted)
├── dots/<dataset>/               # cached lip dots (.npy) from training/extract.py
└── models/                       # trained encoders (.keras)
```

Android (`app/` is a single-module Gradle root; no Android Studio needed, SDK at `~/Android/Sdk`):

```bash
cd app && ./gradlew assembleDebug testDebugUnitTest      # unit tests: golden dots, DTW parity with Python, .npy writer
adb install -r build/outputs/apk/debug/hush-debug.apk
```
