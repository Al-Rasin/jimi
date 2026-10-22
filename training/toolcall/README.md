# Tool-call fine-tune (FunctionGemma 270M)

Teaches FunctionGemma the app's 43 tools, using the exact prompt the app sends.
Runs on the Mac (M1 8 GB) with MLX. Output: `models/functiongemma-270m-jarvis-Q8_0.gguf`.

## Files
- `make_dataset.py` — builds `training/data/toolcall/{train,valid}.jsonl` from templates (~60 rows per tool + chat rows).
  Phrases in `cases.jsonl` are kept out, so the eval stays fair.
- `finetune.py` — LoRA (rank 16, all layers), loss only on the answer tokens, then fuse + convert to GGUF.
- `cases.jsonl` — 45 held-out test phrases (`tool: null` = should chat).
- `eval.py` — runs the cases through a Mac `llama-completion` with the app's prompt + grammar, prints a score.

## Steps
```bash
# once
uv venv -p 3.12 .venv-llm
uv pip install -p .venv-llm/bin/python mlx-lm huggingface_hub torch sentencepiece protobuf
uv pip install -p .venv-llm/bin/python -e app/src/main/cpp/llama.cpp/gguf-py   # after tools/fetch_llama.sh
.venv-llm/bin/python -c "from huggingface_hub import snapshot_download as d; d('unsloth/functiongemma-270m-it', local_dir='models/hf/functiongemma-270m-it')"

# every time the tools change
./gradlew testDebugUnitTest            # writes app/build/llm/{prompt_prefix.txt,grammar.gbnf,tools.txt}
python3 training/toolcall/make_dataset.py
.venv-llm/bin/python training/toolcall/finetune.py --iters 1200
python3 training/toolcall/eval.py --bin <llama-completion> --model models/functiongemma-270m-jarvis-Q8_0.gguf \
    --context "time 2026-10-22 10:00:00"
```

Push to the phone: `adb push models/functiongemma-270m-jarvis-Q8_0.gguf /sdcard/Android/data/com.alrasin.jarvis/files/models/llm/`.

Note: the prompt prefix must match the app exactly. If a tool, its description or args change, re-run all steps.
