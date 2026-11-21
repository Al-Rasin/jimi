"""Turns fine-tune clips into openWakeWord features ([16, 96] windows) for ft_train.py.

Inputs (git-ignored): models/wakeword/ft/{pos,neg,neg_bn} (ft_generate.py), models/wakeword/user/{pos,neg}
(real phone mic recordings), models/wakeword/feedback/{near,false,hit,missed} (app wake clips, sorted by
what was said: near = no wake word, false = woke without it, hit/missed = had it).
Output: models/wakeword/ft/features.npz
"""
import itertools
import random
import wave
from pathlib import Path

import numpy as np
from openwakeword.utils import AudioFeatures

WW = Path(__file__).resolve().parents[2] / "models/wakeword"
ASSETS = WW / "app-assets/wakeword"
SR = 16000
POS_LEN = 3 * SR      # buffer for a positive: the word ends 0.15-0.45 s before the end, like the live stream
NEG_LEN = 3 * SR
rng = random.Random(0)
nrng = np.random.default_rng(0)


def load(path: Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        assert w.getframerate() == SR and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)


def split(paths, test_frac):
    paths = sorted(paths)
    rng.shuffle(paths)
    n = max(1, int(len(paths) * test_frac))
    return paths[n:], paths[:n]


def background(pool, n):
    """Real room sound from the phone mic (or faint noise) at a random low level."""
    if pool and rng.random() < 0.7:
        src = rng.choice(pool)
        reps = int(np.ceil(n / len(src))) + 1
        src = np.tile(src, reps)
        start = rng.randrange(len(src) - n)
        return src[start:start + n] * rng.uniform(0.05, 0.5)
    return nrng.normal(0, rng.uniform(5, 80), n).astype(np.float32)


def place_end(clip, n, pool, noise=True):
    buf = background(pool, n) if noise else nrng.normal(0, 20, n).astype(np.float32)
    # The score peaks ~0.2 s after the word ends, so the window must hold some audio after it.
    end = n - rng.randrange(int(0.15 * SR), int(0.45 * SR))
    clip = clip[-(end - SR // 10):]
    gain = rng.uniform(0.4, 1.6) if noise else 1.0
    buf[end - len(clip):end] += clip * gain
    return np.clip(buf, -32768, 32767).astype(np.int16)


def place_any(clip, n, pool):
    buf = background(pool, n)
    clip = clip[:n]
    start = rng.randrange(0, n - len(clip) + 1)
    buf[start:start + len(clip)] += clip * rng.uniform(0.4, 1.6)
    return np.clip(buf, -32768, 32767).astype(np.int16)


def pad_raw(clip, n, tail=0.3):
    """Real clips for testing: no mixing, padded with faint noise; `tail` s of noise after the clip."""
    end = n - int(tail * SR)
    clip = clip[-end:]
    buf = nrng.normal(0, 20, n).astype(np.float32)
    buf[end - len(clip):end] += clip
    return np.clip(buf, -32768, 32767).astype(np.int16)


def main():
    feats = AudioFeatures(melspec_model_path=str(ASSETS / "melspectrogram.onnx"),
                          embedding_model_path=str(ASSETS / "embedding_model.onnx"))

    def embed(clips, chunk=256):
        """clips may be a generator: only `chunk` raw clips are held in memory at once (8 GB Mac)."""
        parts, buf = [], []
        for c in clips:
            buf.append(c)
            if len(buf) == chunk:
                parts.append(feats.embed_clips(np.stack(buf), batch_size=64).astype(np.float16))
                buf = []
        if buf:
            parts.append(feats.embed_clips(np.stack(buf), batch_size=64).astype(np.float16))
        return np.concatenate(parts) if parts else np.zeros((0, 28, 96), np.float16)

    syn_pos_tr, syn_pos_te = split((WW / "ft/pos").glob("*.wav"), 0.1)
    syn_neg_tr, syn_neg_te = split((WW / "ft/neg").glob("*.wav"), 0.1)
    bn_neg_tr, bn_neg_te = split((WW / "ft/neg_bn").glob("*.wav"), 0.1)
    # Real phone mic: recorded "hey jimi" + app wake clips that hold it (hit, missed).
    real_pos = [p for d in ("user/pos", "feedback/hit", "feedback/missed") for p in (WW / d).glob("*.wav")]
    usr_pos_tr, usr_pos_te = split(real_pos, 0.3)
    # Real phone mic talk/sound that is not the wake word: room recordings and near-wakes.
    usr_neg_tr, usr_neg_te = split([p for d in ("user/neg", "feedback/near") for p in (WW / d).glob("*.wav")], 0.3)
    false_tr, false_te = split((WW / "feedback/false").glob("*.wav"), 0.3)   # real false wakes
    pool = [load(p) for p in usr_neg_tr + false_tr]
    out = {}

    # Train positives: synthetic once, each real recording 30 times with different mixing.
    pos = itertools.chain((place_end(load(p), POS_LEN, pool) for p in syn_pos_tr),
                          (place_end(load(p), POS_LEN, pool) for p in usr_pos_tr for _ in range(30)))
    out["pos_train"] = embed(pos)[:, -16:]
    # Train negatives: every window of look-alike phrases and real room talk; real false wakes most often.
    neg = itertools.chain((place_any(load(p), NEG_LEN, pool) for p in syn_neg_tr + bn_neg_tr),
                          (place_any(load(p), NEG_LEN, pool) for p in usr_neg_tr for _ in range(5)),
                          (place_any(load(p), NEG_LEN, pool) for p in false_tr for _ in range(30)))
    e = embed(neg)
    out["neg_train"] = np.lib.stride_tricks.sliding_window_view(e, 16, axis=1).transpose(0, 1, 3, 2).reshape(-1, 16, 96)

    # Test sets (never trained on): full streams, scored as "max over all windows" per clip.
    out["t_syn_pos"] = embed([place_end(load(p), POS_LEN, pool) for p in syn_pos_te])
    out["t_syn_neg"] = embed([place_any(load(p), NEG_LEN, pool) for p in syn_neg_te])
    out["t_bn_neg"] = embed([place_any(load(p), NEG_LEN, pool) for p in bn_neg_te])
    out["t_usr_pos"] = embed([pad_raw(load(p), 4 * SR) for p in usr_pos_te])
    out["t_usr_neg"] = embed([pad_raw(load(p), NEG_LEN) for p in usr_neg_te])
    out["t_false"] = embed([pad_raw(load(p), NEG_LEN) for p in false_te])
    for k, v in out.items():
        print(k, v.shape)
    np.savez(WW / "ft/features.npz", **out)


if __name__ == "__main__":
    main()
