"""Self-check for dtw.dtw_batch. Run: python training/test_dtw.py"""
import numpy as np

from dtw import dtw_batch

rng = np.random.default_rng(0)
a = np.cumsum(rng.normal(size=(12, 4)), axis=0).astype(np.float32)  # a smooth random "movement"
slow = np.repeat(a, 2, axis=0)  # same movement, half speed
other = np.cumsum(rng.normal(size=(9, 4)), axis=0).astype(np.float32)

d = dtw_batch([a, slow, other], [a, other])
assert d[0, 0] < 1e-3, "identical clips must have ~0 distance"
assert d[1, 0] < 1e-3, "a slowed-down copy must still match (that's the point of DTW)"
assert d[1, 0] < d[1, 1] and d[2, 1] < d[2, 0], "each clip must be nearest to its own movement"
print("ok")
