#!/usr/bin/env python3
"""LoRA fine-tune of FunctionGemma 270M on the Mac with MLX (PLAN 13.2).

Trains on the exact prompt strings the app sends (no chat template in between) and only on the answer
tokens. Then fuses the adapter, converts to GGUF with the pinned llama.cpp, and writes
models/functiongemma-270m-jarvis-v2-Q8_0.gguf (existing outputs are never replaced).

    uv venv -p 3.12 .venv-llm && uv pip install -p .venv-llm/bin/python mlx-lm huggingface_hub
    .venv-llm/bin/python -c "from huggingface_hub import snapshot_download as d; d('unsloth/functiongemma-270m-it', local_dir='models/hf/functiongemma-270m-it')"
    python3 training/toolcall/make_dataset.py
    .venv-llm/bin/python training/toolcall/finetune.py --iters 1500
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import time
import resource
import sys
import tempfile

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim
from mlx_lm import load
from mlx_lm.tuner.datasets import CacheDataset
from mlx_lm.tuner.trainer import TrainingArgs, train
from mlx_lm.tuner.callbacks import TrainingCallback
from mlx.utils import tree_flatten
from mlx_lm.tuner.utils import linear_to_lora_layers

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = ROOT / "training/data/toolcall"
BASE = ROOT / "models/hf/functiongemma-270m-it"
OUT = ROOT / "training/outputs/toolcall"
LLAMA = ROOT / "app/src/main/cpp/llama.cpp"


class PromptCompletion:
    """Rows → (tokens, offset). Loss only after [offset] = the answer."""

    def __init__(self, path, tokenizer):
        self.rows = [json.loads(l) for l in open(path) if l.strip()]
        self.tok = tokenizer

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        return self.rows[i]

    def process(self, row):
        p = self.tok.encode(row["prompt"], add_special_tokens=False)
        c = self.tok.encode(row["completion"], add_special_tokens=False)
        return p + c, len(p)


ANSWER_MAX = 96   # set from the data in main(); fixed so the loss needs no eval inside the grad


def answer_loss(model, batch, lengths):
    """Cross-entropy on the answer tokens only. The vocab is 262k, so logits for all ~2.4k prompt tokens
    would need ~5 GB; here the LM head runs only on the few answer positions."""
    inputs, targets = batch[:, :-1], batch[:, 1:]
    hidden = model.model(inputs)
    offsets, ends = lengths[:, 0], lengths[:, 1]
    n = ends - offsets                                   # answer length per row
    w = mx.arange(ANSWER_MAX)[None, :]
    idx = mx.clip(offsets[:, None] - 1 + w, 0, inputs.shape[1] - 1)
    h = mx.take_along_axis(hidden, idx[:, :, None], axis=1)
    logits = model.model.embed_tokens.as_linear(h) if model.tie_word_embeddings else model.lm_head(h)
    tgt = mx.take_along_axis(targets, idx, axis=1)
    mask = w < n[:, None]
    ce = nn.losses.cross_entropy(logits, tgt) * mask
    ntoks = mask.sum()
    return ce.astype(mx.float32).sum() / ntoks, ntoks


class Sized(CacheDataset):
    def itemlen(self, idx):
        r = self._data.rows[idx]
        return len(r["prompt"]) + len(r["completion"])


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=1500)
    ap.add_argument("--batch", type=int, default=2)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--val-batches", type=int, default=10)
    ap.add_argument("--report-every", type=int, default=25)
    ap.add_argument("--save-every", type=int, default=250)
    ap.add_argument("--cpu-float32", action="store_true")
    ap.add_argument("--max-seconds", type=int, default=0)
    ap.add_argument("--stop-file", type=pathlib.Path)
    ap.add_argument("--skip-train", action="store_true", help="only fuse + convert the last adapter")
    ap.add_argument("--base", type=pathlib.Path, default=BASE)
    ap.add_argument("--data", type=pathlib.Path, default=DATA)
    ap.add_argument("--out", type=pathlib.Path, default=OUT, help="adapter directory")
    ap.add_argument("--llama", type=pathlib.Path, default=LLAMA, help="llama.cpp source directory")
    ap.add_argument("--gguf", type=pathlib.Path, default=ROOT / "models/functiongemma-270m-jarvis-v2-Q8_0.gguf")
    a = ap.parse_args()

    if a.gguf.exists():
        ap.error(f"refusing to overwrite {a.gguf}; choose a new --gguf path")
    convert = a.llama / "convert_hf_to_gguf.py"
    if not convert.is_file():
        ap.error(f"converter not found: {convert}; pass --llama with the pinned llama.cpp source")
    a.out.mkdir(parents=True, exist_ok=True)
    adapter = a.out / "adapters.safetensors"
    if not a.skip_train and adapter.exists():
        ap.error("adapter already exists; choose a new --out directory for training")
    if not a.skip_train:
        mx.random.seed(0)
        model, tok = load(str(a.base))
        if a.cpu_float32:
            model.set_dtype(mx.float32)
        model.freeze()
        lora = {"rank": a.rank, "scale": 2.0, "dropout": 0.0,
                "keys": ["self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj",
                         "mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]}
        linear_to_lora_layers(model, len(model.layers), lora)
        (a.out / "adapter_config.json").write_text(json.dumps(
            {"fine_tune_type": "lora", "num_layers": len(model.layers), "lora_parameters": lora}, indent=2))
        train_set = Sized(PromptCompletion(a.data / "train.jsonl", tok))
        valid_set = Sized(PromptCompletion(a.data / "valid.jsonl", tok))
        global ANSWER_MAX
        ANSWER_MAX = max(len(tok.encode(r["completion"], add_special_tokens=False))
                         for d in (train_set, valid_set) for r in d._data.rows) + 1
        warm = min(50, a.iters // 5)
        sched = optim.join_schedules([optim.linear_schedule(1e-6, a.lr, warm), optim.cosine_decay(a.lr, a.iters - warm, 1e-6)], [warm])
        args = TrainingArgs(batch_size=a.batch, iters=a.iters, val_batches=a.val_batches, steps_per_report=min(a.report_every, a.iters), steps_per_eval=250,
                            steps_per_save=a.save_every, adapter_file=str(adapter), max_seq_length=3072, grad_checkpoint=True)
        try:
            train(model=model, optimizer=optim.AdamW(learning_rate=sched, weight_decay=0.0), train_dataset=train_set,
                  val_dataset=valid_set, args=args, loss=answer_loss,
                  training_callback=BudgetCallback(a.max_seconds, a.stop_file))
        except TrainingStopped as stopped:
            print(stopped, flush=True)
            mx.save_safetensors(str(adapter), dict(tree_flatten(model.trainable_parameters())))
        del model, tok, train_set, valid_set
        mx.clear_cache()

    if not adapter.is_file() or not (a.out / "adapter_config.json").is_file():
        ap.error(f"adapter weights/config missing in {a.out}")
    adapter_hash = hashlib.sha256(adapter.read_bytes()).hexdigest()
    fused = pathlib.Path(tempfile.mkdtemp(prefix="fused-v2-", dir=a.out))
    subprocess.run([sys.executable, "-m", "mlx_lm", "fuse", "--model", str(a.base), "--adapter-path", str(a.out),
                    "--save-path", str(fused), "--dequantize"], check=True)
    for f in ("tokenizer.model", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "added_tokens.json", "chat_template.jinja"):
        if (a.base / f).exists():
            shutil.copy(a.base / f, fused / f)
    if hashlib.sha256(adapter.read_bytes()).hexdigest() != adapter_hash:
        sys.exit("adapter changed during fusion; refusing to export")
    a.gguf.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(convert), str(fused), "--outtype", "q8_0", "--outfile", str(a.gguf)], check=True)
    manifest = {"base": str(a.base.resolve()), "adapter": str(adapter.resolve()),
                "adapter_sha256": adapter_hash, "fused": str(fused.resolve()),
                "converter": str(convert.resolve()), "gguf": str(a.gguf.resolve())}
    a.gguf.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("wrote", a.gguf)


if __name__ == "__main__":
    main()
