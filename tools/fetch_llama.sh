#!/usr/bin/env bash
# Gets llama.cpp source at the pinned commit (D-019) into app/src/main/cpp/llama.cpp (git-ignored).
# The app builds the local LLM (libjarvis_llm.so) only when this folder exists.
# Then push GGUF models to the phone:
#   adb push models/functiongemma-270m-it-Q8_0.gguf models/Qwen3-0.6B-Q4_0.gguf \
#     /sdcard/Android/data/com.alrasin.jarvis/files/models/llm/
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/cpp/llama.cpp
COMMIT=0a1ef30
if [ ! -d "$DEST/.git" ]; then
  git clone https://github.com/ggml-org/llama.cpp "$DEST"
fi
git -C "$DEST" fetch --quiet origin || true
git -C "$DEST" checkout --quiet "$COMMIT"
echo "llama.cpp at $(git -C "$DEST" rev-parse --short HEAD)"
