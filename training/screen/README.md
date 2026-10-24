# Screen model fine-tune (ROADMAP #66, D-070)

Improves SmolLM2-135M-android-control so it picks the next UI action on OUR phone (Xiaomi MIUI) from the
accessibility element list. Prompt format is fixed (the base model was trained on it): see `prompt.py`.
Output: `models/smollm2-135m-jarvis-screen-Q8_0.gguf`. Runs on the Mac (M1 8 GB) with MLX.

## Files
- `prompt.py` — SYS prompt, `elements()` (uiautomator XML to UI list), user turn, answer parsing, `score()`. Shared by all.
- `privacy.py` — `looks_private(label)`: digits >= 6, `@`, words from optional git-ignored `private_words.txt`.
- `collect.py` — adb walk of allow-listed SYSTEM apps (Settings, Security, Clock, Calculator, Files, Camera). Saves XML to `training/data/screen/raw/`.
- `make_goals.py` — goals per screen from templates ("Open X", "Go back", "Scroll down"); `--llm-goals` adds teacher-written ones.
- `serve_teachers.sh` — starts ONE llama-server: `a` = Qwen3-4B (largest Ollama blob), `b` = Qwen3-1.7B, or any gguf path.
- `label.py` — teacher labelling (`--teacher a|b`), then `--merge` keeps rows where both teachers give the same action.
- `make_dataset.py` — `train.jsonl` / `valid.jsonl` (chat format, split by screen). Eval screens are excluded.
- `finetune.py` — MLX LoRA (rank 32, all linear layers, answer-only loss, lr 2e-4 cosine, max len 1280), fuse, GGUF Q8_0.
- `cases.py`, `eval.py` — the 15 held-out cases, scored against a llama-server.
- `run_overnight.sh` — whole chain after collect; log in `training/outputs/screen/overnight-<date>.log`.

## Steps
```bash
# once: .venv-llm with mlx-lm (see training/toolcall/README.md); tools/fetch_llama.sh for the GGUF converter
# Qwen3-1.7B: ollama pull qwen3:1.7b, then link its blob to models/gui-test/qwen3-1.7b.gguf (serve_teachers.sh prints how)

# 1. phone connected, unlocked, awake
export ANDROID_SERIAL=<serial>
python3 training/screen/collect.py --depth 2 --max-screens 40 --settings-actions
# 2. everything else (about hours; teachers run one at a time)
bash training/screen/run_overnight.sh            # add: --limit 50 for a short test
# stop training early: touch training/outputs/screen/STOP
```
Push to the phone: `adb push models/smollm2-135m-jarvis-screen-*-Q8_0.gguf /sdcard/Android/data/com.alrasin.jarvis/files/models/llm/`.

## Replay data
`OfficerChul/Android-Control-84k` has screenshots and x,y answers only, no accessibility text. It cannot be used in
our text format, so `--replay` adds nothing today. Use `--replay-file` for your own rows in chat format.

## Privacy rules
- Only allow-listed system apps are opened. Never messaging, contacts, dialer, gallery, notes, browser. Never type text, never toggle switches.
- Screens with phone numbers, emails, long digit runs or words from `private_words.txt` are dropped; buttons with risky words (reset, erase, account, call, ...) are not tapped.
- Raw dumps, labels, datasets and outputs live only in `training/data/` and `training/outputs/` (git-ignored); models in `models/`.
- The eval screens (`/tmp/guitest/*.xml`, `home.xml` has private text) stay outside the repo; `cases.py` holds only indices and goals.
- Do not commit data. Review `raw/` once before training.
