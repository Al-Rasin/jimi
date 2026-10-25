#!/usr/bin/env bash
# Overnight chain (ROADMAP #66). Run AFTER collect.py (needs the phone). Teachers run one at a time.
#   bash training/screen/run_overnight.sh [--limit N]      # N = rows per teacher, for a short test
# Env: LLM_GOALS=1 (teacher A also writes natural goals), ITERS (default 600), LR (default 2e-4).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
S=training/screen
PY="$ROOT/.venv-llm/bin/python"
DATE="$(date +%Y%m%d-%H%M)"
LOG="training/outputs/screen/overnight-$DATE.log"
mkdir -p training/outputs/screen training/data/screen
exec > >(tee -a "$LOG") 2>&1
LIMIT_ARGS=()
[ "${1:-}" = "--limit" ] && LIMIT_ARGS=(--limit "${2:?number}")

SERVER_PID=""
stop_server() {
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; SERVER_PID=""; fi
}
trap stop_server EXIT

start_server() {   # $1 = a|b|gguf path, $2 = port
  "$S/serve_teachers.sh" "$1" "$2" > "training/outputs/screen/server-$2.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 120); do
    curl -sf "http://127.0.0.1:$2/health" > /dev/null && return 0
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "server died, see training/outputs/screen/server-$2.log"; return 1; }
    sleep 2
  done
  echo "server did not start"; return 1
}

echo "== $(date) start; log $LOG"
start_server a 8081
if [ "${LLM_GOALS:-0}" = 1 ]; then python3 "$S/make_goals.py" --llm-goals --port 8081; else python3 "$S/make_goals.py"; fi
python3 "$S/label.py" --teacher a --port 8081 ${LIMIT_ARGS[@]+"${LIMIT_ARGS[@]}"}
stop_server
start_server b 8082
python3 "$S/label.py" --teacher b --port 8082 ${LIMIT_ARGS[@]+"${LIMIT_ARGS[@]}"}
stop_server
python3 "$S/label.py" --merge
python3 "$S/make_dataset.py"
GGUF="models/smollm2-135m-jarvis-screen-$DATE-Q8_0.gguf"
"$PY" "$S/finetune.py" --out "training/outputs/screen/adapter-$DATE" --gguf "$GGUF" \
  --iters "${ITERS:-600}" --lr "${LR:-2e-4}" \
  --stop-file training/outputs/screen/STOP --max-seconds 10800
[ -f "$GGUF" ] || { echo "no gguf produced (converter missing?)"; exit 1; }
start_server "$GGUF" 8083
python3 "$S/eval.py" --port 8083 --name "jarvis-screen-$DATE"
stop_server
echo "== $(date) done; model $GGUF"
