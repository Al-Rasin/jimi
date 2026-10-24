#!/usr/bin/env python3
"""LoRA fine-tune of SmolLM2-135M-android-control on the Mac with MLX (ROADMAP #66, D-070).

Rank 32 on all linear layers, loss only on the answer tokens, lr 2e-4 cosine. Then fuse the adapter and convert
to GGUF Q8_0 with the pinned llama.cpp (tools/fetch_llama.sh). Existing outputs are never replaced.

    python3 training/screen/make_dataset.py
    .venv-llm/bin/python training/screen/finetune.py --iters 600
"""
import argparse
import hashlib
import json
import pathlib
import resource
import shutil
import subprocess
import sys
import tempfile
import time

import mlx.core as mx
import mlx.optimizers as optim
import mlx.nn as nn
from mlx.utils import tree_flatten
from mlx_lm import load
from mlx_lm.tuner.callbacks import TrainingCallback
from mlx_lm.tuner.datasets import CacheDataset
from mlx_lm.tuner.trainer import TrainingArgs, train
from mlx_lm.tuner.utils import linear_to_lora_layers

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from prompt import IM_END, chat_text  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = ROOT / "training/data/screen"
BASE = ROOT / "models/gui-test/smollm2-ac"
OUT = ROOT / "training/outputs/screen"
LLAMA = ROOT / "app/src/main/cpp/llama.cpp"
MAX_LEN = 1280


class ChatRows:
    """Rows {"messages":[system,user,assistant]} -> (tokens, offset). Loss only after [offset] = the answer."""

    def __init__(self, path, tokenizer):
        self.rows = [json.loads(l) for l in open(path) if l.strip()]
        self.tok = tokenizer

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        return self.rows[i]

    def process(self, row):
        s, u, a = (m["content"] for m in row["messages"])
        p = self.tok.encode(chat_text(s, u), add_special_tokens=False)
        c = self.tok.encode(a + IM_END, add_special_tokens=False)
        return (p + c)[:MAX_LEN], min(len(p), MAX_LEN)


def answer_loss(model, batch, lengths):
    inputs, targets = batch[:, :-1], batch[:, 1:]
    logits = model(inputs)
    steps = mx.arange(1, targets.shape[1] + 1)[None, :]
    mask = (steps >= lengths[:, 0:1]) & (steps <= lengths[:, 1:2])
    ce = nn.losses.cross_entropy(logits.astype(mx.float32), targets) * mask
    ntoks = mask.sum()
    return ce.sum() / ntoks, ntoks


class Sized(CacheDataset):
    def itemlen(self, idx):
        return sum(len(m["content"]) for m in self._data.rows[idx]["messages"])


class TrainingStopped(Exception):
    pass


class BudgetCallback(TrainingCallback):
    def __init__(self, seconds, stop_file):
        self.started = time.monotonic()
        self.seconds = seconds
        self.stop_file = stop_file

    def on_train_loss_report(self, info):
        elapsed = time.monotonic() - self.started
        print(f"Runtime: iteration={info['iteration']} elapsed={elapsed:.1f}s "
              f"peak_rss={resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e9:.3f}GB", flush=True)
        if (self.seconds and elapsed >= self.seconds) or (self.stop_file and self.stop_file.exists()):
            raise TrainingStopped(f"stopped after {info['iteration']} iterations ({elapsed:.1f}s)")


def run_training(a, adapter):
    mx.random.seed(0)
    model, tok = load(str(a.base))
    model.freeze()
    lora = {"rank": a.rank, "scale": 2.0, "dropout": 0.0,
            "keys": ["self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj",
                     "mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]}
    linear_to_lora_layers(model, len(model.layers), lora)
    (a.out / "adapter_config.json").write_text(json.dumps(
        {"fine_tune_type": "lora", "num_layers": len(model.layers), "lora_parameters": lora}, indent=2))
    train_set = Sized(ChatRows(a.data / "train.jsonl", tok))
    valid_set = Sized(ChatRows(a.data / "valid.jsonl", tok))
    warm = min(50, a.iters // 5)
    sched = optim.join_schedules([optim.linear_schedule(1e-6, a.lr, warm),
                                  optim.cosine_decay(a.lr, a.iters - warm, 1e-6)], [warm])
    args = TrainingArgs(batch_size=a.batch, iters=a.iters, val_batches=a.val_batches,
                        steps_per_report=min(a.report_every, a.iters), steps_per_eval=a.eval_every,
                        steps_per_save=a.save_every, adapter_file=str(adapter), max_seq_length=MAX_LEN,
                        grad_checkpoint=True)
    try:
        train(model=model, optimizer=optim.AdamW(learning_rate=sched, weight_decay=0.0), train_dataset=train_set,
              val_dataset=valid_set, args=args, loss=answer_loss,
              training_callback=BudgetCallback(a.max_seconds, a.stop_file))
    except TrainingStopped as stopped:
        print(stopped, flush=True)
        mx.save_safetensors(str(adapter), dict(tree_flatten(model.trainable_parameters())))


def export(a, adapter, convert):
    adapter_hash = hashlib.sha256(adapter.read_bytes()).hexdigest()
    fused = pathlib.Path(tempfile.mkdtemp(prefix="fused-", dir=a.out))
    subprocess.run([sys.executable, "-m", "mlx_lm", "fuse", "--model", str(a.base), "--adapter-path", str(a.out),
                    "--save-path", str(fused)], check=True)
    for f in ("tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "vocab.json", "merges.txt",
              "added_tokens.json", "chat_template.jinja", "generation_config.json"):
        if (a.base / f).exists():
            shutil.copy(a.base / f, fused / f)
    a.gguf.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(convert), str(fused), "--outtype", "q8_0", "--outfile", str(a.gguf)], check=True)
    manifest = {"base": str(a.base.resolve()), "adapter_sha256": adapter_hash, "fused": str(fused.resolve()),
                "converter": str(convert.resolve()), "gguf": str(a.gguf.resolve())}
    a.gguf.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("wrote", a.gguf)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=600)
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rank", type=int, default=32)
    ap.add_argument("--val-batches", type=int, default=10)
    ap.add_argument("--report-every", type=int, default=20)
    ap.add_argument("--eval-every", type=int, default=100)
    ap.add_argument("--save-every", type=int, default=200)
    ap.add_argument("--max-seconds", type=int, default=0, help="time limit; 0 = none")
    ap.add_argument("--stop-file", type=pathlib.Path, help="touch this file to stop early (the adapter is still saved)")
    ap.add_argument("--skip-train", action="store_true", help="only fuse + convert the existing adapter")
    ap.add_argument("--base", type=pathlib.Path, default=BASE)
    ap.add_argument("--data", type=pathlib.Path, default=DATA)
    ap.add_argument("--out", type=pathlib.Path, default=OUT, help="adapter directory (must be new for training)")
    ap.add_argument("--llama", type=pathlib.Path, default=LLAMA, help="llama.cpp source directory")
    ap.add_argument("--gguf", type=pathlib.Path, default=ROOT / "models/smollm2-135m-jarvis-screen-Q8_0.gguf")
    a = ap.parse_args()

    if a.gguf.exists():
        ap.error(f"refusing to overwrite {a.gguf}; choose a new --gguf path")
    convert = a.llama / "convert_hf_to_gguf.py"
    adapter = a.out / "adapters.safetensors"
    if not a.skip_train and adapter.exists():
        ap.error("adapter already exists; choose a new --out directory for training")
    a.out.mkdir(parents=True, exist_ok=True)
    if not a.skip_train:
        run_training(a, adapter)
        mx.clear_cache()
    if not adapter.is_file():
        ap.error(f"adapter weights missing in {a.out}")
    if not convert.is_file():
        print(f"converter not found: {convert}\nRun tools/fetch_llama.sh, then:\n"
              f"  .venv-llm/bin/python training/screen/finetune.py --skip-train --out {a.out} --gguf {a.gguf}")
        return
    export(a, adapter, convert)


if __name__ == "__main__":
    main()
