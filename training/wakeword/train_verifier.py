"""Train the wake verifier (D-100) on all real phone clips and write verifier.bin for the app.

Positives: owner's real "Hey Jimi" clips (user/pos, feedback/hit, feedback/missed + labeled newer clips).
Negatives: TV / talk / near misses (user/neg, feedback/near, feedback/false + labeled newer clips).
Each clip is scored at 4 phases (0/20/40/60 ms), like the app's detector; top windows are kept.

Usage: .venv-ww/bin/python training/wakeword/train_verifier.py <out verifier.bin> [<labels.tsv> <clip dir>]...
Push:  adb push verifier.bin /sdcard/Android/data/com.alrasin.jimi/files/models/wakeword/verifier.bin
The file holds the owner's voice features: keep it out of git.
"""
import sys

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

import eval_real as e

PHASES = (0, 320, 640, 960)   # samples; app PHASE_STEP = 2 mel frames = 320 samples
C = 0.001                     # eval_real: 0.0005..0.01 gave the same results


def all_windows(audio):
    ws, ss = [], []
    for off in PHASES:
        pad = np.zeros(e.SR + off, np.int16)
        emb = e.feats.embed_clips(np.concatenate([pad, audio])[None, :], batch_size=1)[0]
        w = np.stack([emb[i - 16:i] for i in range(16, emb.shape[0] + 1)]).astype(np.float32)
        ws.append(w)
        ss.append(np.array([e.wake.run(None, {e.wake_in: x[None]})[0].item() for x in w]))
    return np.concatenate(ws), np.concatenate(ss)


def main():
    out = sys.argv[1]
    pos, neg = e.old_real()
    for i in range(2, len(sys.argv), 2):
        p, n, _ = e.label_rows(sys.argv[i], sys.argv[i + 1])   # ambiguous (foreign-language) clips skipped
        pos += [a for _, a in p]
        neg += [a for _, a in n]
    print(f"clips: pos {len(pos)} neg {len(neg)}")
    X, y = [], []
    for a in pos:
        w, s = all_windows(a)
        for i in np.argsort(s)[-6:]:
            X.append(w[i].ravel()); y.append(1)
    for a in neg:
        w, s = all_windows(a)
        for i in np.argsort(s)[-8:]:   # hardest windows
            X.append(w[i].ravel()); y.append(0)
    X, y = np.array(X), np.array(y)
    clf = make_pipeline(StandardScaler(), LogisticRegression(C=C, max_iter=3000, class_weight="balanced")).fit(X, y)
    sc, lr = clf[0], clf[1]
    p = clf.predict_proba(X)[:, 1]
    print(f"train: pos kept@0.3 {np.mean(p[y == 1] >= 0.3):.0%}  neg passed@0.3 {np.mean(p[y == 0] >= 0.3):.0%}")

    blob = np.concatenate([sc.mean_, sc.scale_, lr.coef_[0], lr.intercept_]).astype("<f4")
    # same math as WakeVerifier.score
    z = ((X[:5] - blob[:1536]) / blob[1536:3072]) @ blob[3072:4608] + blob[4608]
    assert np.allclose(1 / (1 + np.exp(-z)), p[:5], atol=1e-3)
    blob.tofile(out)
    print(f"wrote {out} ({blob.nbytes} bytes)")


if __name__ == "__main__":
    main()
