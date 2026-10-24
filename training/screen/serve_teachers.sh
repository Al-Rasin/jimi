#!/usr/bin/env bash
# Start ONE teacher llama-server in the foreground (8 GB Mac: never two at once).
#   serve_teachers.sh a [port]   Qwen3-4B  (largest blob in ~/.ollama/models/blobs)  default port 8081
#   serve_teachers.sh b [port]   Qwen3-1.7B (see QWEN17 below)                       default port 8082
#   serve_teachers.sh /path/model.gguf [port]   any other gguf (used for the new model in the eval)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERVER="${LLAMA_SERVER:-/opt/homebrew/bin/llama-server}"
BLOBS="$HOME/.ollama/models/blobs"
QWEN17="${QWEN17:-$ROOT/models/gui-test/qwen3-1.7b.gguf}"
QWEN4_FALLBACK="$BLOBS/sha256-3e4cb14174460404e7a233e531675303b2fbf7749c02f91864fe311ab6344e4f"

which_model="${1:?usage: serve_teachers.sh a|b|/path/model.gguf [port]}"
case "$which_model" in
  a)
    port="${2:-8081}"
    model="$(ls -S "$BLOBS"/sha256-* 2>/dev/null | head -1 || true)"
    [ -n "$model" ] || model="$QWEN4_FALLBACK"
    ;;
  b)
    port="${2:-8082}"
    model="$QWEN17"
    if [ ! -f "$model" ]; then
      echo "Qwen3-1.7B not found at $model." >&2
      echo "Get it:  ollama pull qwen3:1.7b" >&2
      echo "Then:    cat ~/.ollama/models/manifests/registry.ollama.ai/library/qwen3/1.7b   # 'application/vnd.ollama.image.model' digest" >&2
      echo "         ln -s $BLOBS/sha256-<that digest> $QWEN17     # or export QWEN17=<blob path>" >&2
      exit 1
    fi
    ;;
  *)
    port="${2:-8083}"
    model="$which_model"
    ;;
esac
[ -f "$model" ] || { echo "model file missing: $model" >&2; exit 1; }
echo "serving $model on port $port"
exec "$SERVER" -m "$model" --port "$port" --host 127.0.0.1 -c 4096 -t 4
