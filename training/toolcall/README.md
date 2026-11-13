# Tool-call fine-tune (FunctionGemma 270M)

Teaches FunctionGemma the app's 43 tools, using the exact prompt the app sends.
Runs on the Mac (M1 8 GB) with MLX. Output: `models/functiongemma-270m-jarvis-v2-Q8_0.gguf`.

## Files
- `make_dataset.py` — builds `training/data/toolcall/{train,valid}.jsonl` from templates (~60 rows per tool + chat rows).
  Phrases in `cases.jsonl` are kept out, so the eval stays fair.
- `finetune.py` — LoRA (rank 16, all layers), loss only on the answer tokens, then fuse + convert to GGUF.
- `cases.jsonl` — 45 held-out test phrases (`tool: null` = should chat).
- `from_learned.py` — rows from what the phone learned (app maps, recipes, ✗-fixed commands) → `training/data/toolcall/learned.jsonl`.
  Private-looking labels are dropped. Add them with `make_dataset.py --learned training/data/toolcall/learned.jsonl`.
- `teacher.py` — Gemini as teacher (#25): many varied commands per tool (Bangla-English mix, speech-to-text slips)
  → `training/data/toolcall/teacher.jsonl`; `--mode chat` writes short spoken Q&A → `training/data/chat/teacher_chat.jsonl`.
  Needs `GEMINI_API_KEY` in env (never saved). Only the tool list goes out. Eval phrases, private-looking and money rows dropped.
  Try `--dry-run` first. Add rows with `make_dataset.py --learned training/data/toolcall/teacher.jsonl` (flag repeats).
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
python3 training/notifications/export_db.py                     # optional: pull jarvis.db (into training/data/notifications/export-*/)
python3 training/toolcall/from_learned.py --db training/data/notifications/export-*/jarvis.db   # and/or --appmap <export>.json
python3 training/toolcall/make_dataset.py --learned training/data/toolcall/learned.jsonl
.venv-llm/bin/python training/toolcall/finetune.py --iters 1200
python3 training/toolcall/eval.py --bin <llama-completion> --model models/functiongemma-270m-jarvis-v2-Q8_0.gguf \
    --context "time 2026-10-22 10:00:00"
```

Push to the phone: `adb push models/functiongemma-270m-jarvis-v2-Q8_0.gguf /sdcard/Android/data/com.alrasin.jarvis/files/models/llm/`.

Note: the prompt prefix must match the app exactly. If a tool, its description or args change, re-run all steps.

## Re-export an existing adapter

Use `finetune.py --skip-train` with `--base`, `--out` (the adapter directory),
`--llama` (the pinned converter source), and a new `--gguf` filename when working
from a separate worktree. Each export creates a fresh `fused-v2-*` directory and
a GGUF provenance manifest. Existing GGUF files and training adapters are protected
against accidental replacement; choose new paths for another run.

The evaluator uses `--binary-file --no-escape`: llama-completion's ordinary `-f`
removes the final newline after `model`, changing the prompt the adapter learned.
Runtime failures now fail the eval instead of counting as chat. Use `--cpu` if
Metal is unavailable, `--prompt-cache /tmp/toolcall-model.cache` to reuse the tool
prefix (use a separate cache for each model), and `--output-jsonl results.jsonl`
to retain raw answers. `--prompt-dir` selects the app's exported prefix and grammar.
Native prompts already contain the chat template; do not apply another template.
For an MLX CLI smoke check, pass `--ignore-chat-template`, remove the leading
literal `<bos>` because MLX adds it, and preserve the final model-turn newline.

## Dataset checks without training

`make_dataset.py` includes 448 distinct chat templates and deterministic STT noise
for tool requests. It excludes phrases from both `cases.jsonl` and `cases_v2.jsonl`,
including common wake/filler wrappers. The new set has 60 held-out phrases (25 chat,
20 noisy commands, 15 clean commands). First-word dropping is restricted to
redundant command prefixes; free-text tool arguments are preserved.

```bash
python3 -m unittest discover -s training/toolcall/tests -q
python3 training/toolcall/make_dataset.py --out /tmp/jimi-toolcall-data
```

`--out` leaves the default output location unchanged. If a worktree has no generated
prompt export, use `--prefix /path/to/existing/prompt_prefix.txt` to read an existing
export without rebuilding the app. The default chat budget is up to 600 unique rows,
capped by available templates. The script prints counts per tool and chat share.

## Round 1

`make_round1.py` builds the separate Round 1 train/dev/locked-test release;
`r1_contract.py` reads the current Kotlin registry and argument declarations.
`r1_public.py` acquires pinned public source data and uses `convert_public.py`
for conservative mappings with attribution. No training is run by these scripts.
Datasets and downloaded
sources belong outside git; keep both existing `cases*.jsonl` fixtures unchanged.
