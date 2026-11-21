"""Offline check of wake-word fixes on real phone clips (D-100).

Replays app wake clips through melspectrogram -> embedding -> hey_jimi model, then tests:
  - a small verifier (logistic regression on the 16x96 window, openWakeWord "custom verifier" idea)
  - a speaker check (CAM++ cosine vs the owner's voice)
Train data = older real clips (already seen by the D-092 model); test data = newer labeled clips.

Usage: .venv-ww/bin/python training/wakeword/eval_real.py <labels.tsv> <clip dir>
labels.tsv rows: id, score, verdict, hasName(0/1), file, transcript  (see pull_feedback.sh + whisper)
"""
import sys
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort
from openwakeword.utils import AudioFeatures

WW = Path(__file__).resolve().parents[2] / "models/wakeword"
ASSETS = WW / "app-assets/wakeword"
SR = 16000


def load(path):
    with wave.open(str(path)) as w:
        if w.getframerate() != SR:
            return None
        return np.frombuffer(w.readframes(w.getnframes()), np.int16)


feats = AudioFeatures(melspec_model_path=str(ASSETS / "melspectrogram.onnx"),
                      embedding_model_path=str(ASSETS / "embedding_model.onnx"))
wake = ort.InferenceSession(str(WW / "ft/hey_jimi_ft_final.onnx"))
wake_in = wake.get_inputs()[0].name


def windows(audio):
    """All [16, 96] windows of one clip and the wake score of each."""
    pad = np.zeros(SR, np.int16)   # the live stream has audio before the clip; silence stands in for it
    emb = feats.embed_clips(np.concatenate([pad, audio])[None, :], batch_size=1)[0]
    w = np.stack([emb[i - 16:i] for i in range(16, emb.shape[0] + 1)]).astype(np.float32)
    s = np.array([wake.run(None, {wake_in: x[None]})[0].item() for x in w])   # model takes batch 1
    return w, s


def label_rows(tsv, clip_dir):
    pos, neg, amb = [], [], []
    for line in open(tsv):
        r = line.rstrip("\n").split("\t")
        a = load(Path(clip_dir) / r[4])
        if a is None or len(a) < 2.4 * SR:
            continue   # pre-D-091 clips are 0.48 s and hold no wake audio
        t = r[5].lower()
        if r[3] == "1" or r[2] == "yes":
            pos.append((r[0], a))
        elif r[2] == "no" or "foreign" not in t:
            neg.append((r[0], a))
        else:
            amb.append((r[0], a))
    return pos, neg, amb


def old_real():
    pos = [load(p) for d in ("user/pos", "feedback/hit", "feedback/missed") for p in sorted((WW / d).glob("*.wav"))]
    neg = [load(p) for d in ("user/neg", "feedback/near", "feedback/false") for p in sorted((WW / d).glob("*.wav"))]
    keep = lambda xs: [x for x in xs if x is not None and len(x) >= 0.9 * SR]
    return keep(pos), keep(neg)


def speaker():
    import sherpa_onnx
    cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=str(WW.parent / "speech/app-assets/speaker/campplus.onnx"), num_threads=2)
    ex = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)

    def embed(a):
        s = ex.create_stream()
        s.accept_waveform(SR, (a.astype(np.float32) / 32768.0))
        s.input_finished()
        v = np.array(ex.compute(s))
        return v / np.linalg.norm(v)
    return embed


def main():
    pos, neg, amb = label_rows(sys.argv[1], sys.argv[2])
    print(f"test: pos {len(pos)} neg {len(neg)} ambiguous(foreign) {len(amb)}")
    opos, oneg = old_real()
    print(f"train(old): pos {len(opos)} neg {len(oneg)}")

    # 1) verifier: positive windows = top-scoring windows of each positive clip; negatives = all windows
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    X, y = [], []
    for a in opos:
        w, s = windows(a)
        for i in np.argsort(s)[-3:]:
            X.append(w[i].ravel()); y.append(1)
    for a in oneg:
        w, s = windows(a)
        X.extend(x.ravel() for x in w); y.extend([0] * len(w))
    for C in (0.0005, 0.001, 0.01):
        clf = make_pipeline(StandardScaler(), LogisticRegression(C=C, max_iter=2000, class_weight="balanced"))
        clf.fit(np.array(X), np.array(y))
        report(f"verifier C={C}", clf, pos, neg, amb)
    # base model alone for comparison
    print("\nbase model peak score per clip:")
    for name, set_ in (("pos", pos), ("neg", neg), ("amb", amb)):
        print(f"  {name}: " + " ".join(f"{i}:{windows(a)[1].max():.2f}" for i, a in set_))

    # 2) speaker similarity vs mean of old real positives (all user's own voice)
    embed = speaker()
    owner = np.mean([embed(a) for a in opos[:29]], axis=0)   # user/pos = recorded by the owner
    owner /= np.linalg.norm(owner)
    print("\nspeaker cosine (last 1.6 s of clip):")
    for name, set_ in (("pos", pos), ("neg", neg), ("amb", amb)):
        sc = [(i, float(embed(a[-int(1.6 * SR):]) @ owner)) for i, a in set_]
        print(f"  {name}: " + " ".join(f"{i}:{v:.2f}" for i, v in sc))


def report(tag, clf, pos, neg, amb):
    def best(a):
        w, s = windows(a)
        top = np.argsort(s)[-4:]   # verifier runs on the windows near the wake peak
        return clf.predict_proba(w[top].reshape(len(top), -1))[:, 1].max()
    p = np.array([best(a) for _, a in pos]); n = np.array([best(a) for _, a in neg])
    print(f"\n{tag}: pos min/med {p.min():.2f}/{np.median(p):.2f}  neg max/med {n.max():.2f}/{np.median(n):.2f}")
    for t in (0.1, 0.3, 0.5):
        print(f"  thr {t}: pos kept {np.mean(p >= t):.0%}  neg passed {np.mean(n >= t):.0%}")
    print("  amb: " + " ".join(f"{i}:{best(a):.2f}" for i, a in amb))


if __name__ == "__main__":
    main()
