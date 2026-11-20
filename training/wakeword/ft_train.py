"""Fine-tunes the current hey_jimi.onnx on ft/features.npz (ft_features.py) to cut false wakes,
then prints old vs new so the better model can be picked.

  python ft_train.py --neg_weight 60 --steps 4000 --lr 2e-4 --out ../../models/wakeword/ft/hey_jimi_ft.onnx
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import onnx
import torch
from onnx import numpy_helper
from torch import nn

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_fast import Net, fp_per_hour, scores  # noqa: E402

WW = Path(__file__).resolve().parents[2] / "models/wakeword"
OLD = WW / "app-assets/wakeword/hey_jimi.onnx"
TH = 0.95   # app threshold


def load_onnx(path: Path) -> Net:
    weights = {i.name: torch.from_numpy(numpy_helper.to_array(i).copy()) for i in onnx.load(str(path)).graph.initializer}
    model = Net(weights["f.1.weight"].shape[0])
    model.load_state_dict(weights)
    return model.eval()


def clip_max(model, streams):
    """Per clip: highest score over all 16-frame windows, like the live detector."""
    win = np.lib.stride_tricks.sliding_window_view(streams, 16, axis=1).transpose(0, 1, 3, 2)
    s = scores(model, win.reshape(-1, 16, 96)).reshape(win.shape[0], win.shape[1])
    return s.max(axis=1)


def report(name, model, d, val):
    row = {k: float(np.mean(clip_max(model, d[k]) >= TH)) for k in d.files if k.startswith("t_")}
    row["val_fp_h"] = fp_per_hour(model, val, TH)
    print(f"{name:>4}: " + "  ".join(f"{k[2:] if k.startswith('t_') else k} {v:.3f}" for k, v in row.items()), flush=True)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--neg_weight", type=float, default=60)
    ap.add_argument("--acav_weight", type=float, default=None, help="weight for general audio; default = neg_weight")
    ap.add_argument("--steps", type=int, default=4000)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--acav_rows", type=int, default=300_000)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=str(WW / "ft/hey_jimi_ft.onnx"))
    a = ap.parse_args()
    rng = np.random.default_rng(a.seed)
    torch.manual_seed(a.seed)

    d = np.load(WW / "ft/features.npz")
    pos, neg = d["pos_train"], d["neg_train"]
    acav_all = np.load(WW / "data/acav_part.npy", mmap_mode="r")
    acav = np.asarray(acav_all[np.sort(rng.choice(len(acav_all), a.acav_rows, replace=False))])
    val = np.load(WW / "data/validation_set_features.npy")
    print(f"pos {len(pos)}  neg {len(neg)}  acav {len(acav)}")

    report("old", load_onnx(OLD), d, val)
    model = load_onnx(OLD).train()
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.steps)
    bce = nn.BCELoss(reduction="none")
    for step in range(a.steps):
        xb = np.concatenate([pos[rng.integers(0, len(pos), 256)], neg[rng.integers(0, len(neg), 512)],
                             acav[rng.integers(0, len(acav), 1024)]]).astype(np.float32)
        y = torch.from_numpy(np.concatenate([np.ones(256), np.zeros(1536)]).astype(np.float32))
        p = model(torch.from_numpy(xb))[:, 0]
        w = torch.ones_like(y)
        w[256:768] = a.neg_weight
        w[768:] = a.neg_weight if a.acav_weight is None else a.acav_weight
        keep = ((y == 0) & (p >= 0.001)) | ((y == 1) & (p < 0.999))   # hard examples only
        if keep.sum() == 0:
            continue
        loss = (bce(p[keep], y[keep]) * w[keep]).sum() / max(int(keep.sum()), 128)
        opt.zero_grad()
        loss.backward()
        opt.step()
        sched.step()
    model.eval()
    report("new", model, d, val)
    torch.onnx.export(model, torch.rand(1, 16, 96), a.out, input_names=["x"], output_names=["score"], opset_version=13)
    print("saved", a.out)


if __name__ == "__main__":
    main()
