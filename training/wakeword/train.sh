#!/usr/bin/env bash
# Train the "Hey Jimi" openWakeWord model on the Mac (CPU). Run from anywhere:
#   PY=/path/to/venv/bin/python training/wakeword/train.sh [generate|user|augment|train|all]
# Steps are resumable: generated clips already on disk are kept.
set -euo pipefail
cd "$(dirname "$0")/../.."                      # repo root
PY="${PY:-python3}"
WW=models/wakeword
CFG=training/wakeword/hey_jimi.yml
OWW_COMMIT=368c03716d1e92591906a84949bc477f3a834455   # openWakeWord train.py (tflite behind a flag)
TRAIN="$WW/oww_train.py"
OUT="$WW/out/hey_jimi"
USER_POS_COPIES=40    # each real "Hey Jimi" clip is used this many times (augmentation makes each copy different)
USER_NEG_COPIES=5

[ -f "$TRAIN" ] || curl -sfL -o "$TRAIN" \
  "https://raw.githubusercontent.com/dscripka/openWakeWord/$OWW_COMMIT/openwakeword/train.py"

step="${1:-all}"

if [[ $step == generate || $step == all ]]; then
  "$PY" training/wakeword/run_oww_train.py "$TRAIN" --training_config "$CFG" --generate_clips
fi

if [[ $step == user || $step == all ]]; then
  # Real recordings from the phone (see README): models/wakeword/user/{pos,neg}/*.wav
  for f in "$WW"/user/pos/*.wav; do
    [ -e "$f" ] || break
    for i in $(seq 1 $USER_POS_COPIES); do cp "$f" "$OUT/positive_train/user_$(basename "$f" .wav)_$i.wav"; done
  done
  for f in "$WW"/user/neg/*.wav; do
    [ -e "$f" ] || break
    for i in $(seq 1 $USER_NEG_COPIES); do cp "$f" "$OUT/negative_train/user_$(basename "$f" .wav)_$i.wav"; done
  done
  echo "positive_train: $(ls "$OUT/positive_train" | wc -l)  negative_train: $(ls "$OUT/negative_train" | wc -l)"
fi

if [[ $step == augment || $step == all ]]; then
  "$PY" training/wakeword/run_oww_train.py "$TRAIN" --training_config "$CFG" --augment_clips
fi

if [[ $step == train || $step == all ]]; then
  "$PY" training/wakeword/run_oww_train.py "$TRAIN" --training_config "$CFG" --train_model
  ls -la "$WW/out/hey_jimi.onnx"
fi
