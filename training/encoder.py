"""Phase 4: tiny lip encoder (D-05). Clip of lip dots -> 64-number fingerprint.

Trained only on GRID words, tested on MIRACL with the exact dtw.py protocol, so the numbers compare directly.

  python training/encoder.py train    # GRID words -> data/models/encoder.keras (+ MIRACL score)
  python training/encoder.py eval     # MIRACL teach/test with fingerprints (float model)
  python training/encoder.py export   # int8 -> app/src/main/assets/lip_encoder.tflite (+ MIRACL score of the int8 model)
"""
import sys
from pathlib import Path

import numpy as np

from dtw import evaluate, fill_gaps, load

ROOT = Path(__file__).resolve().parent.parent
GRID_DOTS, GRID_ALIGN = ROOT / "data/dots/grid", ROOT / "data/grid/alignments"
MIRACL_DOTS = ROOT / "data/dots/miracl"
MODEL = ROOT / "data/models/encoder.keras"
TFLITE = ROOT / "app/src/main/assets/lip_encoder.tflite"
L = 32  # every clip is resampled to 32 frames: same input whatever the camera fps (14-30)
F = 80  # 40 dots x (x, y)
VAL_SPEAKERS = {"s1", "s2"}  # held out from training to watch overfitting


def resample(clip, n=L):
    """(T, F) -> (n, F) by linear interpolation in time."""
    t = np.linspace(0, len(clip) - 1, n)
    i = np.floor(t).astype(int)
    j = np.minimum(i + 1, len(clip) - 1)
    f = (t - i)[:, None]
    return (clip[i] * (1 - f) + clip[j] * f).astype(np.float32)


def align_dirs():
    """Video folder -> alignment folder. The Zenodo labels disagree from s10 on (s10 videos = s13 alignments),
    so pair them by the sentence names they contain; each set of 1000 names matches exactly one folder."""
    names = {a.name: {p.stem for p in a.glob("*.align")} for a in GRID_ALIGN.iterdir() if a.is_dir()}
    pairs = {}
    for v in GRID_DOTS.iterdir():
        stems = {p.stem for p in v.glob("*.npy")}
        best = max(names, key=lambda a: len(stems & names[a]))
        if stems & names[best]:
            pairs[v.name] = GRID_ALIGN / best
    return pairs


def grid_words():
    """GRID sentences cut into word clips with the alignments (1000 units = 1 frame at 25 fps)."""
    clips, words, speakers = [], [], []
    dirs = align_dirs()
    for f in sorted(GRID_DOTS.glob("s*/*.npy")):
        if f.parent.name not in dirs:
            continue
        a = dirs[f.parent.name] / (f.stem + ".align")
        d = fill_gaps(np.load(f)) if a.exists() else None
        if d is None:
            continue
        for line in a.read_text().split("\n"):
            parts = line.split()
            if len(parts) != 3 or parts[2] in ("sil", "sp"):
                continue
            i0, i1 = max(int(parts[0]) // 1000 - 2, 0), min(int(parts[1]) // 1000 + 2, len(d))  # +-2 frames context
            if i1 - i0 >= 4:
                clips.append(d[i0:i1].astype(np.float32))
                words.append(parts[2])
                speakers.append(f.parent.name)
    return clips, words, speakers


def augment(clip, rng):
    """Mimic phone conditions: lower/variable fps, sloppy button timing, jitter, slight tilt and size changes."""
    keep = rng.uniform(0.45, 1.0)  # 25 fps GRID -> ~11-25 fps
    clip = resample(clip, max(4, round(len(clip) * keep)))
    a, b = rng.integers(0, 2, size=2)
    if len(clip) - a - b >= 4:
        clip = clip[a: len(clip) - b]
    x = resample(clip).reshape(L, 40, 2)
    ang = rng.normal(0, 0.05)
    rot = np.array([[np.cos(ang), -np.sin(ang)], [np.sin(ang), np.cos(ang)]], dtype=np.float32)
    x = x @ rot.T * rng.uniform(0.93, 1.07) + rng.normal(0, 0.01, x.shape)
    return x.reshape(L, F).astype(np.float32)


def build(n_classes):
    import keras
    from keras import layers

    inp = keras.Input((L, F))
    x = inp
    for filters, k, s in [(128, 5, 1), (128, 5, 1), (192, 3, 2), (192, 3, 1)]:
        x = layers.Conv1D(filters, k, strides=s, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
    x = layers.Concatenate()([layers.GlobalAveragePooling1D()(x), layers.GlobalMaxPooling1D()(x)])
    x = layers.Dropout(0.3)(x)
    emb = layers.UnitNormalization()(layers.Dense(64)(x))
    encoder = keras.Model(inp, emb, name="lip_encoder")

    # Cosine classifier (NormFace): logits = 16 * cos(fingerprint, class centre). Trains fingerprints
    # so that the same word lands close together, which is exactly what nearest-take matching needs.
    centres = layers.Dense(n_classes, use_bias=False, kernel_constraint=keras.constraints.UnitNorm(axis=0))
    logits = layers.Rescaling(16.0)(centres(emb))
    return encoder, keras.Model(inp, logits)


def embed(encoder_fn, clips):
    return encoder_fn(np.stack([resample(c) for c in clips]))


def miracl_score(encoder_fn):
    data, _ = load(MIRACL_DOTS)
    return evaluate(data, dist_fn=lambda a, b: 1 - embed(encoder_fn, a) @ embed(encoder_fn, b).T)


def train(epochs=40, batch=256, seed=0):
    import keras
    import tensorflow as tf

    clips, words, speakers = grid_words()
    vocab = sorted(set(words))
    y = np.array([vocab.index(w) for w in words])
    val = np.array([s in VAL_SPEAKERS for s in speakers])
    tr_idx, va_idx = np.where(~val)[0], np.where(val)[0]
    print(f"{len(clips)} word clips, {len(vocab)} words, {len(set(speakers))} speakers "
          f"({len(tr_idx)} train / {len(va_idx)} validation clips)", flush=True)
    rng = np.random.default_rng(seed)

    def gen():
        while True:
            for i in rng.permutation(tr_idx):
                yield augment(clips[i], rng), y[i]

    ds = tf.data.Dataset.from_generator(gen, output_signature=(tf.TensorSpec((L, F), tf.float32), tf.TensorSpec((), tf.int64)))
    ds = ds.batch(batch).prefetch(4)
    xv = np.stack([resample(clips[i]) for i in va_idx])
    steps = len(tr_idx) // batch
    encoder, model = build(len(vocab))
    print(f"encoder parameters: {encoder.count_params():,}", flush=True)
    model.compile(keras.optimizers.Adam(keras.optimizers.schedules.CosineDecay(2e-3, epochs * steps)),
                  keras.losses.SparseCategoricalCrossentropy(from_logits=True), metrics=["accuracy"])
    model.fit(ds, steps_per_epoch=steps, epochs=epochs, validation_data=(xv, y[va_idx]), verbose=2)
    MODEL.parent.mkdir(parents=True, exist_ok=True)
    encoder.save(MODEL)
    print(f"saved {MODEL}")
    miracl_score(lambda x: encoder.predict(x, batch_size=512, verbose=0))


def export():
    import keras
    import tensorflow as tf

    encoder = keras.models.load_model(MODEL)
    clips, _, _ = grid_words()
    rng = np.random.default_rng(1)
    rep = [resample(clips[i])[None] for i in rng.choice(len(clips), 500, replace=False)]
    conv = tf.lite.TFLiteConverter.from_keras_model(encoder)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = lambda: ([r] for r in rep)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]  # full int8 (D-05); float in/out keeps the app simple
    TFLITE.write_bytes(conv.convert())
    print(f"wrote {TFLITE} ({TFLITE.stat().st_size / 1024:.0f} KB)")

    interp = tf.lite.Interpreter(model_path=str(TFLITE))
    interp.allocate_tensors()
    i, o = interp.get_input_details()[0]["index"], interp.get_output_details()[0]["index"]

    def run(x):
        out = []
        for row in x:
            interp.set_tensor(i, row[None])
            interp.invoke()
            out.append(interp.get_tensor(o)[0])
        out = np.array(out)
        return out / np.linalg.norm(out, axis=1, keepdims=True)

    print("int8 model on MIRACL:")
    miracl_score(run)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "train":
        train()
    elif cmd == "eval":
        import keras
        enc = keras.models.load_model(MODEL)
        miracl_score(lambda x: enc.predict(x, batch_size=512, verbose=0))
    elif cmd == "export":
        export()
    else:
        sys.exit(__doc__)
