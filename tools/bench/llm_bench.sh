#!/usr/bin/env bash
# Phase 0.2 — run llama-bench on the phone for every GGUF in models/.
# Needs: adb connected, llama-bench built for Android arm64 (see docs/BENCHMARKS.md).
# Usage: tools/bench/llm_bench.sh <adb-serial> <path-to-llama-bench> [models-dir]
set -euo pipefail
SERIAL="$1"; BENCH="$2"; MODELS="${3:-models}"
DEV=/data/local/tmp/jb
A="adb -s $SERIAL"

$A shell mkdir -p $DEV
$A push "$BENCH" $DEV/llama-bench >/dev/null
$A shell chmod 755 $DEV/llama-bench

for m in "$MODELS"/*.gguf; do
  name=$(basename "$m")
  $A push "$m" "$DEV/$name" >/dev/null
  echo "## $name"
  # prompt 128 tokens, generate 64 tokens, 2 repeats, different thread counts
  $A shell "$DEV/llama-bench -m $DEV/$name -p 128 -n 64 -r 2 -t 2,4,6,8 -o md" 2>/dev/null
  echo
  $A shell rm -f "$DEV/$name"
done
