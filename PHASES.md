# Hush — Phases

Deadline: **2026-10-31** (prototype). Docker stays off until the user says otherwise (D-32).

**Big idea of the order:** get a working phone demo early using the zero-training DTW matcher (D-14), then make it smarter. If the neural model runs late, we still have a demo.

| # | Phase | Dates | What gets built | Done when |
|---|---|---|---|---|
| 0 | **Setup** ✅ | Oct 3 | `.venv` (Python 3.11 via uv), pinned `training/requirements.txt`, `face_landmarker.task` (now in `app/src/main/assets/`), minimal Compose app in `app/`. GRID video + alignments in `data/grid/` (`training/fetch_grid.sh`). User: download MIRACL-VC1, start own recordings with [training/RECORDING.md](training/RECORDING.md) (LRW and OuluVS2 skipped, D-40/D-41) | `python -c "import mediapipe, tensorflow"` works; the empty Compose app runs on the phone |
| 1 | **Lip dots + golden file** ✅ | Oct 3 (done early) | `training/extract.py`: video file *or* folder of frames (MIRACL is frames) → 40 lip dots per frame, centred, scaled, tilt removed → `.npy`. Also saves a golden file (one clip's raw landmarks + expected dots) | Dots drawn over a MIRACL clip follow the lips; golden file saved. **Result:** all 3,000 MIRACL clips → `data/dots/miracl/` in 4.5 min; 0.6 % of frames without a face, 8 clips with none at all (F04/M01 sitting far from the camera; irrelevant on a phone held close) |
| 2 | **DTW baseline + test method** ✅ | Oct 3 (done early) | `training/dtw.py`: teach with 5 reps per phrase, test on held-out reps per speaker; "none" threshold; wrong-accept rate on non-phrase clips | Accuracy and wrong-accept numbers on MIRACL — the bar the neural model must beat. **Result (bar to beat, after D-47):** top-1 **86.1 %**, top-3 **96.3 %** (15 speakers, 20 items, 5 takes taught). At ratio 0.8: 56 % accepted correctly, **0.3 %** misreads accepted, 8.6 % untaught clips accepted. Own phone takes (4 phrases, leave-one-out): 25/25. **After D-49 (movement only, cross-session robust):** MIRACL top-1 81.8 %, top-3 94.3 %; live confirmed clips 5/5 ranked first. The encoder must beat DTW on both same-session (MIRACL) and live phone clips |
| 3 | **Phone app end to end + record mode** | Oct 8–16 | CameraX + MediaPipe in Kotlin, push-to-talk, Teach, DTW match, Confirm (2nd/3rd guess chips), actions (SMS, call, alarm, speak aloud), light + dark theme. Hidden record mode saving dot sequences only. Teach stores dot sequences, not fingerprints (D-37) | Mouth "I need water" → it speaks. Kotlin dots match the golden file within 1e-4. FPS logged with CPU-only MediaPipe at 320×240. **First demoable prototype** |
| 4 | **Neural encoder (fixed time)** | Oct 17–23 | Keras 1D-CNN on GRID/MIRACL/own recordings, 15 fps augmentation, fingerprint fine-tune, int8 `.tflite` < 1 MB, swapped in for DTW | Beats DTW, < 10 ms on the phone. **Go/no-go Oct 23: otherwise ship with DTW** |
| 5 | **Own data + tuning** | Oct 24–27 | Own recordings (phone videos from Oct 4, app record mode from ~Oct 14), "none" threshold, nod-to-confirm, latency table filled | ≥ 90 % on own phrases, 0 wrong-accepts for sends/calls in testing |
| 6 | **Demo prep (buffer)** | Oct 28–31 | Bug fixes, demo script, optional Ask AI, README | Full demo runs 3× in a row without a fix |

Solo developer (D-36), so phases run one after another.

**Cut first if late:** Ask AI → nod-to-confirm (tap still works) → neural encoder (ship with DTW).

**Risks to watch:** no 2 GB test phone (only mid-range) — latency on the target device is estimated, so try to borrow a cheap phone before Phase 5. Disk is no longer tight (78 GB free on Oct 3).
