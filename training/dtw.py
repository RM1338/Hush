"""DTW baseline (D-14): teach with a few takes per phrase, recognise the rest. Zero training.

Test method (D-36), per speaker, 2 folds (teach takes 1-5 / test 6-10, then swapped):
  closed set  - all 20 MIRACL items taught: top-1 and top-3 accuracy.
  open set    - 5 random items left untaught; their clips should come back "none".
                Accept the best guess only if best distance <= ratio x second-best distance (D-46);
                a lower ratio = stricter. Misread accepted = a known phrase accepted as the wrong one.

  python training/dtw.py data/dots/miracl
"""
import sys
from pathlib import Path

import numpy as np

MAX_MISSING = 0.5  # skip clips where more than half the frames had no face


def fill_gaps(d):
    """Linear-interpolate NaN frames; None if too many are missing."""
    ok = ~np.isnan(d[:, 0, 0])
    if ok.mean() <= 1 - MAX_MISSING:
        return None
    t = np.arange(len(d))
    flat = d.reshape(len(d), -1)
    return np.stack([np.interp(t, t[ok], flat[ok, c]) for c in range(flat.shape[1])], axis=1)


def features(d):
    """Dot positions minus the clip's average mouth shape: keeps the movement, drops posture/session offsets (D-49).
    Costs ~4 pts on same-session MIRACL but ranked 5/5 live phone clips first vs 1/5 with raw positions."""
    return (d - d.mean(axis=0)).astype(np.float32)


def dtw_batch(a_list, b_list):
    """DTW cost for every pair (a_i, b_j), normalised by path length bound. Returns (len(a), len(b))."""
    A, B = pad(a_list).astype(np.float64), pad(b_list).astype(np.float64)  # float64: the dot-product form loses ~1e-4 in float32
    la, lb = [len(a) for a in a_list], [len(b) for b in b_list]
    sq = (A ** 2).sum(-1)[:, None, :, None] + (B ** 2).sum(-1)[None, :, None, :]
    cost = np.sqrt(np.maximum(sq - 2 * np.einsum("itf,jsf->ijts", A, B), 0))  # (Na, Nb, Ta, Tb) frame distances
    D = np.full(cost.shape, np.inf)
    Ta, Tb = cost.shape[2:]
    for i in range(Ta):
        for j in range(Tb):
            if i == 0 and j == 0:
                best = 0
            else:
                best = np.minimum(np.minimum(D[:, :, i - 1, j] if i else np.inf, D[:, :, i, j - 1] if j else np.inf),
                                  D[:, :, i - 1, j - 1] if i and j else np.inf)
            D[:, :, i, j] = cost[:, :, i, j] + best
    ia, ib = np.array(la) - 1, np.array(lb) - 1
    end = D[np.arange(len(la))[:, None], np.arange(len(lb))[None, :], ia[:, None], ib[None, :]]
    return end / (ia[:, None] + ib[None, :] + 2)


def phrase_distance(d):
    """A phrase's distance = mean of its 2 nearest takes (one take if only one). +1.2 pts top-1 vs nearest only (D-47)."""
    return np.sort(d, axis=1)[:, :2].mean(axis=1)


def pad(seqs):
    T = max(len(s) for s in seqs)
    out = np.full((len(seqs), T, seqs[0].shape[1]), np.nan, dtype=np.float32)
    for k, s in enumerate(seqs):
        out[k, : len(s)] = s
    return np.nan_to_num(out, nan=1e3)  # padding is never on a valid path's end, so its cost doesn't matter


def load(root):
    """-> {speaker: {item: {take: features}}}, item like 'phrases/03'."""
    data, skipped = {}, 0
    for f in sorted(Path(root).glob("*/*/*/*.npy")):
        spk, kind, item = f.parts[-4:-1]
        d = fill_gaps(np.load(f))
        if d is None:
            skipped += 1
            continue
        data.setdefault(spk, {}).setdefault(f"{kind}/{item}", {})[int(f.stem)] = features(d)
    return data, skipped


def evaluate(data, dist_fn=None, ratios=(0.6, 0.7, 0.8, 0.9, 1.0), n_unknown=5, seed=0):
    """dist_fn(test_clips, template_clips) -> (tests, templates) distances; default DTW. Returns closed-set top-1."""
    dist_fn = dist_fn or dtw_batch
    rng = np.random.default_rng(seed)
    top1 = top3 = n_closed = 0
    open_ratio, open_correct, open_unknown = [], [], []
    for items in data.values():
        names = sorted(items)
        unknown = set(rng.choice(names, n_unknown, replace=False))
        for teach_takes in (range(1, 6), range(6, 11)):
            tpl, tpl_lab, tst, tst_lab = [], [], [], []
            for name in names:
                for take, x in items[name].items():
                    (tpl if take in teach_takes else tst).append(x)
                    (tpl_lab if take in teach_takes else tst_lab).append(name)
            tpl_lab, tst_lab = np.array(tpl_lab), np.array(tst_lab)
            dist = dist_fn(tst, tpl)  # (tests, templates)

            # closed set
            per_item = np.stack([phrase_distance(dist[:, tpl_lab == n]) for n in names], axis=1)
            rank = np.argsort(per_item, axis=1)
            truth = np.array([names.index(lab) for lab in tst_lab])
            top1 += (rank[:, 0] == truth).sum()
            top3 += (rank[:, :3] == truth[:, None]).any(axis=1).sum()
            n_closed += len(truth)

            # open set: only known items taught; untaught clips must be rejected
            known = [n for n in names if n not in unknown]
            per_known = per_item[:, [names.index(n) for n in known]]
            two = np.sort(per_known, axis=1)[:, :2]
            open_ratio += list(two[:, 0] / two[:, 1])
            open_correct += list(np.array(known)[per_known.argmin(axis=1)] == tst_lab)
            open_unknown += list(np.isin(tst_lab, list(unknown)))
    r, ok, unk = np.array(open_ratio), np.array(open_correct), np.array(open_unknown)
    print(f"closed set ({len(data)} speakers, 20 items, {n_closed} test clips):"
          f"  top-1 {top1 / n_closed:.1%}   top-3 {top3 / n_closed:.1%}")
    print(f"open set (15 taught + 5 untaught items per speaker; {(~unk).sum()} known / {unk.sum()} untaught clips):")
    print("  ratio   known accepted correctly   known misread accepted   untaught accepted")
    for q in ratios:
        acc = r <= q
        print(f"  {q:5.1f}   {(acc & ok)[~unk].mean():24.1%}   {(acc & ~ok)[~unk].mean():22.1%}   {acc[unk].mean():17.1%}")
    return top1 / n_closed


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    data, skipped = load(sys.argv[1])
    print(f"loaded {sum(len(t) for i in data.values() for t in i.values())} clips, skipped {skipped} (mostly no face)")
    evaluate(data)
