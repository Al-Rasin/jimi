#!/usr/bin/env bash
# Download sherpa-onnx (AAR) + speech models for the app (D-025). Nothing here goes into git.
#   tools/fetch_speech.sh
# Result:
#   app/libs/sherpa-onnx-static-link-onnxruntime-<ver>.aar
#   models/speech/app-assets/stt/{encoder,decoder,joiner}.int8.onnx, tokens.txt   (streaming zipformer en 20M)
#   models/speech/app-assets/tts/en_GB-alan-medium.onnx, tokens.txt, espeak-ng-data/  (Piper voice "alan")
# Already-downloaded files are kept.
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$(sed -n 's/^sherpa-onnx = "\(.*\)"/\1/p' gradle/libs.versions.toml)
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
AAR=app/libs/sherpa-onnx-static-link-onnxruntime-$VER.aar
DL=models/dl
OUT=models/speech/app-assets
STT=sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
TTS=vits-piper-en_GB-alan-medium
mkdir -p app/libs "$DL" "$OUT/stt" "$OUT/tts"

[ -f "$AAR" ] || curl -fL -o "$AAR" "$REL/v$VER/$(basename "$AAR")"
[ -f "$DL/$STT.tar.bz2" ] || curl -fL -o "$DL/$STT.tar.bz2" "$REL/asr-models/$STT.tar.bz2"
[ -f "$DL/$TTS.tar.bz2" ] || curl -fL -o "$DL/$TTS.tar.bz2" "$REL/tts-models/$TTS.tar.bz2"

if [ ! -f "$OUT/stt/tokens.txt" ]; then
  tar xjf "$DL/$STT.tar.bz2" -C "$DL"
  for p in encoder decoder joiner; do cp "$DL/$STT/$p-epoch-99-avg-1.int8.onnx" "$OUT/stt/$p.int8.onnx"; done
  cp "$DL/$STT/tokens.txt" "$OUT/stt/"
fi
if [ ! -f "$OUT/tts/tokens.txt" ]; then
  tar xjf "$DL/$TTS.tar.bz2" -C "$DL"
  cp -R "$DL/$TTS/en_GB-alan-medium.onnx" "$DL/$TTS/tokens.txt" "$DL/$TTS/espeak-ng-data" "$OUT/tts/"
fi
du -sh "$AAR" "$OUT"/*
