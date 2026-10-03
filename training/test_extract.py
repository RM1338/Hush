"""Self-check for extract.normalise. Run: python training/test_extract.py"""
import numpy as np

from extract import LEFT, LIPS, RIGHT, normalise

rng = np.random.default_rng(0)
W, H = 640, 480
lips = rng.uniform(0.4, 0.6, (5, len(LIPS), 2))  # 5 frames of random lip points, xy in 0..1
lips[2] = np.nan  # a frame with no face

# Move, rotate and scale the whole mouth in pixel space: the dots must not change.
px = lips * [W, H]
a, s, t = np.radians(17), 1.6, np.array([-40.0, 25.0])
R = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
moved = ((px - px.mean(axis=(0, 1), where=~np.isnan(px))) @ R.T * s + [W / 2, H / 2] + t) / [W, H]

d1, d2 = normalise(lips, W, H), normalise(moved, W, H)
ok = ~np.isnan(d1[:, 0, 0])
assert np.allclose(d1[ok], d2[ok], atol=1e-5), "dots changed under move/rotate/scale"
assert np.allclose(d1[ok][:, LEFT], [-0.5, 0], atol=1e-6) and np.allclose(d1[ok][:, RIGHT], [0.5, 0], atol=1e-6)
assert np.isnan(d1[2]).all(), "missing face must stay NaN"
assert len(set(LIPS)) == 40
print("ok")
