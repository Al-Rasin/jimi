#!/usr/bin/env bash
# Copy wake-word feedback clips from the phone (D-023, PLAN 3.6) to models/wakeword/feedback/<verdict>/.
# Clip names: <yyyyMMdd_HHmmss>_s<score*100>_<verdict>.wav, verdict = yes | no | missed | near | none.
# Never deletes anything on the phone. Safe to run again (only copies).
#   ANDROID_SERIAL=<ip:port> training/wakeword/pull_feedback.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC=/sdcard/Android/data/com.alrasin.jarvis/files/wake
OUT=models/wakeword/feedback
TMP="$OUT/_pull"
mkdir -p "$TMP"
adb pull "$SRC/." "$TMP/" >/dev/null
for f in "$TMP"/*.wav; do
  [ -e "$f" ] || break
  v="${f##*_}"; v="${v%.wav}"
  mkdir -p "$OUT/$v"
  mv -f "$f" "$OUT/$v/"
done
rmdir "$TMP" 2>/dev/null || true
for d in "$OUT"/*/; do echo "$(basename "$d"): $(ls "$d" | wc -l | tr -d ' ')"; done
