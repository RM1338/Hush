# Hush — Phases

Deadline: **2026-10-31** (prototype). Docker stays off until the user says otherwise (D-32).

**Big idea of the order:** get a working phone demo early using the zero-training DTW matcher (D-14), then make it smarter. If the neural model runs late, we still have a demo.

| # | Phase | Dates | What gets built | Done when |
|---|---|---|---|---|
| 0 | **Setup** | Oct 2 | Python venv (`tensorflow-cpu mediapipe numpy`), Android Studio + SDK, apply for LRW, download MIRACL-VC1 | `python -c "import mediapipe, tensorflow"` works; an empty Compose app runs on the phone |
| 1 | **Lip dots** | Oct 2–4 | `training/extract.py`: video → 40 lip dots per frame, centred, scaled, tilt removed → `.npy` | Dots drawn over a MIRACL clip follow the lips |
| 2 | **DTW baseline (laptop)** | Oct 4–6 | `training/dtw.py`: teach with 5 clips per phrase, match the rest, "none" threshold | An accuracy number on MIRACL phrases — the bar the neural model must beat |
| 3 | **Phone app, end to end** | Oct 6–14 | CameraX + MediaPipe in Kotlin (same dot code as phase 1), push-to-talk, Teach, DTW match, Confirm (2nd/3rd guess chips), actions (SMS, call, alarm, speak aloud), light + dark theme | Mouth "I need water" on a cheap phone → it speaks. **First demoable prototype** |
| 4 | **Neural encoder** | Oct 9–20 (parallel to 3) | Keras 1D-CNN on GRID/MIRACL (+LRW if approved), fingerprint fine-tune, int8 `.tflite` < 1 MB, swapped in for DTW | Beats DTW accuracy, < 10 ms on the phone |
| 5 | **Real-world data & tuning** | Oct 15–24 | Own recordings (30 phrases × 5+ testers, real phones, varied light), "none" threshold, nod-to-confirm, latency measured on a low-end phone | ≥ 90 % on own phrases, zero wrong sends/calls in testing, latency table filled with real numbers |
| 6 | **Demo prep** | Oct 25–31 | Bug fixes, demo script, optional Ask AI (text, opt-in) if time allows, README | Full demo runs 3× in a row without a fix |

**Cut first if late:** Ask AI → nod-to-confirm (tap still works) → LRW → neural encoder (ship with DTW).

**Risk to watch:** disk. 6.4 GB free; Android SDK + emulator ≈ 5–8 GB, datasets several GB. Use a real phone instead of an emulator, and keep datasets on an external drive if possible.
