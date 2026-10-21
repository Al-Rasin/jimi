"""Fast wake word trainer on features already computed by openWakeWord's train.py (--augment_clips).

Why: openWakeWord's auto_train keeps raising the negative weight (1500 → 3000 → 6000) and gave a model
that caught only 26% of test positives. Here the negative weight is fixed and every run prints
recall + false wakes per hour, so a good balance can be picked in minutes. Same model shape as
openWakeWord "dnn" (input [1,16,96] → score), so the app loads it the same way.

  python train_fast.py --neg_weight 30 --layer 64 --steps 8000 --out ../../models/wakeword/out/hey_jimi_fast.onnx
"""
import argparse
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

ROOT = Path(__file__).resolve().parents[2]
WW = ROOT / "models/wakeword"
FEAT = WW / "out/hey_jimi"
VAL_HOURS = 11.3  # validation_set_features.npy length (openWakeWord)


class Net(nn.Module):
    def __init__(self, layer: int):
        super().__init__()
        self.f = nn.Sequential(
            nn.Flatten(),
            nn.Linear(16 * 96, layer), nn.LayerNorm(layer), nn.ReLU(),
            nn.Linear(layer, layer), nn.LayerNorm(layer), nn.ReLU(),
            nn.Linear(layer, 1), nn.Sigmoid(),
        )

    def forward(self, x):
        return self.f(x)


def scores(model, x, batch=8192):
    out = []
    with torch.no_grad():
        for i in range(0, len(x), batch):
            out.append(model(torch.from_numpy(np.ascontiguousarray(x[i:i + batch], dtype=np.float32)))[:, 0].numpy())
    return np.concatenate(out)


def fp_per_hour(model, val, th):
    """Streaming-style: windows of 16 frames, stride 1; count wakes with a 1.5 s (~19 frame) gap."""
    win = np.lib.stride_tricks.sliding_window_view(val, (16, 96))[:, 0]
    s = scores(model, win)
    hits, last = 0, -100
    for i in np.flatnonzero(s >= th):
        if i - last > 19:
            hits += 1
        last = i
    return hits / VAL_HOURS


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--neg_weight", type=float, default=30)
    ap.add_argument("--layer", type=int, default=64)
    ap.add_argument("--steps", type=int, default=8000)
    ap.add_argument("--acav_rows", type=int, default=300_000)
    ap.add_argument("--out", default=str(WW / "out/hey_jimi_fast.onnx"))
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    rng = np.random.default_rng(a.seed)
    torch.manual_seed(a.seed)

    pos = np.load(FEAT / "positive_features_train.npy")
    neg = np.load(FEAT / "negative_features_train.npy")
    acav_all = np.load(WW / "data/acav_part.npy", mmap_mode="r")
    pick = np.sort(rng.choice(len(acav_all), a.acav_rows, replace=False))
    acav = np.asarray(acav_all[pick], dtype=np.float16)  # float16 keeps RAM low
    pos_test = np.load(FEAT / "positive_features_test.npy")
    neg_test = np.load(FEAT / "negative_features_test.npy")
    val = np.load(WW / "data/validation_set_features.npy")
    print(f"pos {len(pos)}  neg {len(neg)}  acav {len(acav)}  val {len(val)} frames")

    model = Net(a.layer)
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=1e-3, total_steps=a.steps, pct_start=0.1)
    bce = nn.BCELoss(reduction="none")
    t0 = time.time()
    for step in range(a.steps):
        xb = np.concatenate([
            pos[rng.integers(0, len(pos), 256)],
            neg[rng.integers(0, len(neg), 256)],
            acav[rng.integers(0, len(acav), 1024)],
        ]).astype(np.float32)
        yb = np.concatenate([np.ones(256), np.zeros(1280)]).astype(np.float32)
        # ramp the negative weight in over the first half so positives are learned first
        wn = 1 + (a.neg_weight - 1) * min(1.0, step / (a.steps / 2))
        w = torch.from_numpy(np.where(yb == 1, 1.0, wn).astype(np.float32))
        p = model(torch.from_numpy(xb))[:, 0]
        y = torch.from_numpy(yb)
        # openWakeWord trick: learn only from examples that are still wrong-ish (hard examples)
        keep = ((y == 0) & (p >= 0.001)) | ((y == 1) & (p < 0.999))
        if keep.sum() == 0:
            continue
        loss = (bce(p[keep], y[keep]) * w[keep]).sum() / max(int(keep.sum()), 128)
        opt.zero_grad()
        loss.backward()
        opt.step()
        sched.step()
        if (step + 1) % 2000 == 0:
            print(f"step {step + 1} loss {loss.item():.4f} ({time.time() - t0:.0f}s)")

    model.eval()
    ps, ns = scores(model, pos_test), scores(model, neg_test)
    for th in (0.5, 0.7, 0.8, 0.9, 0.95, 0.98):
        print(f"th={th}: recall {np.mean(ps >= th):.3f}  neg_test fp {np.mean(ns >= th):.3f}  "
              f"fp/hour {fp_per_hour(model, val, th):.2f}")
    torch.onnx.export(model, torch.rand(1, 16, 96), a.out, input_names=["x"], output_names=["score"], opset_version=13)
    print("saved", a.out)


if __name__ == "__main__":
    main()
