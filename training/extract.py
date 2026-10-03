"""Video file or folder of frames -> 40 normalised lip dots per frame.

Output: one .npy per clip, float32, shape (frames, 40, 2), NaN rows where no face was found.
The phone app must normalise exactly like `normalise()` (checked by the golden file, D-36).

  python training/extract.py data/miracl/dataset data/dots/miracl           # every clip under a folder
  python training/extract.py --show   data/miracl/dataset/F01/phrases/01/01 out.png
  python training/extract.py --golden data/miracl/dataset/F01/phrases/01/01 training/golden_lips.json
"""
import json
import os
import sys
from multiprocessing import Pool
from pathlib import Path

import cv2
import numpy as np

MODEL = Path(__file__).resolve().parent.parent / "data/models/face_landmarker.task"  # pinned, D-39
VIDEO_EXTS = {".mp4", ".mpg", ".mov", ".avi", ".webm", ".3gp"}
FRAME_FPS = 15  # ponytail: frame folders carry no fps; MIRACL is 15. Only used for tracking timestamps

# MediaPipe FACE_LANDMARKS_LIPS: outer lip ring then inner ring, each going around from the left corner.
LIPS = [61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185,
        78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191]
LEFT, RIGHT = LIPS.index(61), LIPS.index(291)  # mouth corners


def normalise(lips, width, height):
    """(T, 40, 2) MediaPipe xy in 0..1 -> mouth-centred, mouth-width-scaled, head-roll-removed dots.

    After this the left corner is (-0.5, 0) and the right corner (0.5, 0) in every frame.
    """
    p = lips * np.array([width, height], dtype=np.float64)  # pixels, so aspect ratio doesn't skew angles
    left, right = p[:, LEFT], p[:, RIGHT]
    centre = (left + right) / 2
    v = right - left
    w = np.linalg.norm(v, axis=1)
    cos, sin = v[:, 0] / w, v[:, 1] / w
    d = p - centre[:, None]
    x = (d[..., 0] * cos[:, None] + d[..., 1] * sin[:, None]) / w[:, None]
    y = (-d[..., 0] * sin[:, None] + d[..., 1] * cos[:, None]) / w[:, None]
    return np.stack([x, y], axis=-1).astype(np.float32)


def frames(clip):
    """Yield (rgb frame, timestamp ms) from a video file or a folder of images."""
    clip = Path(clip)
    if clip.is_dir():
        files = sorted(clip.glob("color_*.jpg")) or sorted(f for f in clip.iterdir() if f.suffix in {".jpg", ".png"})
        for i, f in enumerate(files):
            yield cv2.cvtColor(cv2.imread(str(f)), cv2.COLOR_BGR2RGB), i * 1000 // FRAME_FPS
        return
    cap = cv2.VideoCapture(str(clip))
    fps = cap.get(cv2.CAP_PROP_FPS) or 25
    i = 0
    while True:
        ok, bgr = cap.read()
        if not ok:
            break
        yield cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), int(i * 1000 / fps)
        i += 1
    cap.release()


def landmarker():
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions, vision
    opts = vision.FaceLandmarkerOptions(base_options=BaseOptions(model_asset_path=str(MODEL)),
                                        running_mode=vision.RunningMode.VIDEO, num_faces=1)
    return mp, vision.FaceLandmarker.create_from_options(opts)


def raw_lips(clip):
    """-> (raw (T, 40, 2) xy in 0..1 with NaN where no face, width, height, list of rgb frames)."""
    mp, lm = landmarker()
    out, imgs = [], []
    for rgb, ts in frames(clip):
        imgs.append(rgb)
        res = lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb)), ts)
        if res.face_landmarks:
            pts = res.face_landmarks[0]
            out.append([[pts[i].x, pts[i].y] for i in LIPS])
        else:
            out.append([[np.nan, np.nan]] * len(LIPS))
    h, w = imgs[0].shape[:2]
    return np.array(out, dtype=np.float64).reshape(-1, len(LIPS), 2), w, h, imgs


def find_clips(root):
    """Video files, and folders that directly contain image frames."""
    for d, _, files in os.walk(root):
        if any(f.endswith((".jpg", ".png")) for f in files):
            yield Path(d)
        yield from (Path(d) / f for f in files if Path(f).suffix.lower() in VIDEO_EXTS)


def _work(job):
    clip, out = job
    lips, w, h, _ = raw_lips(clip)  # fresh landmarker per clip: no tracking state leaks between clips

    out.parent.mkdir(parents=True, exist_ok=True)
    np.save(out, normalise(lips, w, h))
    return int(np.isnan(lips[:, 0, 0]).sum()), len(lips)


def extract_all(root, out_root):
    root, out_root = Path(root), Path(out_root)
    jobs = []
    for clip in find_clips(root):
        rel = clip.relative_to(root)
        out = out_root / (rel.with_suffix(".npy") if clip.is_file() else rel.parent / f"{rel.name}.npy")
        if not out.exists():  # resumable
            jobs.append((clip, out))
    print(f"{len(jobs)} clips to extract", flush=True)
    missed = total = 0
    with Pool(os.cpu_count()) as pool:
        for n, (m, t) in enumerate(pool.imap_unordered(_work, jobs, chunksize=4), 1):
            missed, total = missed + m, total + t
            if n % 200 == 0 or n == len(jobs):
                print(f"{n}/{len(jobs)} clips, {total} frames, {missed} without a face", flush=True)


def show(clip, png, n=6):
    """Contact sheet: raw lip dots (green, corners red) over n frames spread across the clip."""
    lips, w, h, imgs = raw_lips(clip)
    tiles = []
    for i in np.linspace(0, len(imgs) - 1, min(n, len(imgs))).astype(int):
        img = cv2.cvtColor(imgs[i], cv2.COLOR_RGB2BGR)
        if not np.isnan(lips[i, 0, 0]):
            px = (lips[i] * [w, h]).astype(int)
            for j, (x, y) in enumerate(px):
                cv2.circle(img, (x, y), 1, (0, 0, 255) if j in (LEFT, RIGHT) else (0, 255, 0), -1)
            m = int(np.ptp(px[:, 0]) * 0.5)  # margin: half a mouth width
            x0, y0 = px.min(0) - m
            x1, y1 = px.max(0) + m
            img = img[max(y0, 0):y1, max(x0, 0):x1]
        tiles.append(cv2.resize(img, (320, 240)))
    cv2.imwrite(str(png), np.hstack(tiles))
    print(f"wrote {png}: {len(imgs)} frames, {int(np.isnan(lips[:, 0, 0]).sum())} without a face")


def golden(clip, path):
    """Reference for the Kotlin port: raw MediaPipe lip xy + image size in, expected dots out."""
    lips, w, h, _ = raw_lips(clip)
    Path(path).write_text(json.dumps({
        "clip": str(clip), "width": w, "height": h, "lips_index": LIPS,
        "raw": np.round(lips, 7).tolist(),
        "dots": np.round(normalise(np.round(lips, 7), w, h), 6).tolist(),
    }))
    print(f"wrote {path}: {len(lips)} frames")


if __name__ == "__main__":
    a = sys.argv[1:]
    if len(a) == 3 and a[0] == "--show":
        show(a[1], a[2])
    elif len(a) == 3 and a[0] == "--golden":
        golden(a[1], a[2])
    elif len(a) == 2:
        extract_all(a[0], a[1])
    else:
        sys.exit(__doc__)
