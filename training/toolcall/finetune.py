#!/usr/bin/env python3
"""LoRA fine-tune of FunctionGemma 270M on the Mac with MLX (PLAN 13.2).

Trains on the exact prompt strings the app sends (no chat template in between) and only on the answer
tokens. Then fuses the adapter, converts to GGUF with the pinned llama.cpp, and writes
models/functiongemma-270m-jarvis-Q8_0.gguf (ModelBrain prefers files with "jarvis" in the name).

    uv venv -p 3.12 .venv-llm && uv pip install -p .venv-llm/bin/python mlx-lm huggingface_hub
    .venv-llm/bin/python -c "from huggingface_hub import snapshot_download as d; d('unsloth/functiongemma-270m-it', local_dir='models/hf/functiongemma-270m-it')"
    python3 training/toolcall/make_dataset.py
    .venv-llm/bin/python training/toolcall/finetune.py --iters 1500
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import sys

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim
from mlx_lm import load
from mlx_lm.tuner.datasets import CacheDataset
from mlx_lm.tuner.trainer import TrainingArgs, train
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=1500)
    ap.add_argument("--batch", type=int, default=2)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--skip-train", action="store_true", help="only fuse + convert the last adapter")
    a = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    adapter = OUT / "adapters.safetensors"
    if not a.skip_train:
        mx.random.seed(0)
        model, tok = load(str(BASE))
        model.freeze()
        lora = {"rank": a.rank, "scale": 2.0, "dropout": 0.0,
                "keys": ["self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj",
                         "mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]}
        linear_to_lora_layers(model, len(model.layers), lora)
        (OUT / "adapter_config.json").write_text(json.dumps(
            {"fine_tune_type": "lora", "num_layers": len(model.layers), "lora_parameters": lora}, indent=2))
        train_set = Sized(PromptCompletion(DATA / "train.jsonl", tok))
        valid_set = Sized(PromptCompletion(DATA / "valid.jsonl", tok))
        global ANSWER_MAX
        ANSWER_MAX = max(len(tok.encode(r["completion"], add_special_tokens=False))
                         for d in (train_set, valid_set) for r in d._data.rows) + 1
        warm = min(50, a.iters // 5)
        sched = optim.join_schedules([optim.linear_schedule(1e-6, a.lr, warm), optim.cosine_decay(a.lr, a.iters - warm, 1e-6)], [warm])
        args = TrainingArgs(batch_size=a.batch, iters=a.iters, val_batches=10, steps_per_report=min(25, a.iters), steps_per_eval=250,
                            steps_per_save=250, adapter_file=str(adapter), max_seq_length=3072, grad_checkpoint=True)
        train(model=model, optimizer=optim.AdamW(learning_rate=sched, weight_decay=0.0), train_dataset=train_set,
              val_dataset=valid_set, args=args, loss=answer_loss)

    fused = OUT / "fused"
    subprocess.run([sys.executable, "-m", "mlx_lm", "fuse", "--model", str(BASE), "--adapter-path", str(OUT),
                    "--save-path", str(fused), "--dequantize"], check=True)
    for f in ("tokenizer.model", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "added_tokens.json"):
        if (BASE / f).exists():
            shutil.copy(BASE / f, fused / f)
    gguf = ROOT / "models/functiongemma-270m-jarvis-Q8_0.gguf"
    convert = LLAMA / "convert_hf_to_gguf.py"
    if not convert.exists():
        sys.exit(f"fused model in {fused}; run tools/fetch_llama.sh, then re-run with --skip-train to make the GGUF")
    subprocess.run([sys.executable, str(convert), str(fused), "--outtype", "q8_0", "--outfile", str(gguf)], check=True)
    print("wrote", gguf)


if __name__ == "__main__":
    main()
