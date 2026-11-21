"""How many detector phases are needed (D-102)? Peak wake score per clip for several phase sets.

Usage: .venv-ww/bin/python training/wakeword/phase_eval.py <labels.tsv> <clip dir>
"""
import sys

import numpy as np

import eval_real as e
from train_verifier import all_windows   # noqa: F401  (same padding as the app)

SETS = {"1 (0)": (0,), "2 (0,40ms)": (0, 640), "2 (0,20ms)": (0, 320), "3 (0,20,40)": (0, 320, 640),
        "4 (0..60)": (0, 320, 640, 960)}


def peaks(audio):
    out = {}
    for off in (0, 320, 640, 960):
        pad = np.zeros(e.SR + off, np.int16)
        emb = e.feats.embed_clips(np.concatenate([pad, audio])[None, :], batch_size=1)[0]
        w = np.stack([emb[i - 16:i] for i in range(16, emb.shape[0] + 1)]).astype(np.float32)
        out[off] = max(e.wake.run(None, {e.wake_in: x[None]})[0].item() for x in w)
    return out


def main():
    pos, neg, _ = e.label_rows(sys.argv[1], sys.argv[2])
    opos, oneg = e.old_real()
    P = [peaks(a) for _, a in pos] + [peaks(a) for a in opos]
    N = [peaks(a) for _, a in neg] + [peaks(a) for a in oneg]
    print(f"pos {len(P)} neg {len(N)}")
    for name, s in SETS.items():
        p = np.array([max(x[o] for o in s) for x in P]); n = np.array([max(x[o] for o in s) for x in N])
        print(f"{name:12s} " + "  ".join(f"@{t}: pos {np.mean(p >= t):.0%} neg {np.mean(n >= t):.0%}" for t in (0.66, 0.88)))


if __name__ == "__main__":
    main()
