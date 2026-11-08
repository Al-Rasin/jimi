# Benchmarks (Phase 0)

Real measurements from the target phone. Model choices are based on these numbers.

## Device (Phase 0.1 — measured 2026-10-21)
| Item | Value |
|---|---|
| Model | Redmi 10 Prime (`22011119TI`, codename `selene`) |
| SoC | MediaTek mt6768 (Helio G88): 2x 2.0 GHz A75 + 6x 1.8 GHz A55 |
| ABI | arm64-v8a |
| Android / MIUI | Android 11 (SDK 30) / MIUI 12.5.10 (V12.5.10.0.RKUINXM) |
| Root | Magisk 30.7 (`su` → uid 0) ✅ |
| RAM total | 3.64 GB (MemTotal 3,814,920 kB) |
| RAM available (normal use) | ~1.36 GB (MemAvailable 1,426,136 kB) ⚠️ tight |
| Swap (zram) | 2.2 GB |
| Storage free | 27 GB of 47 GB |

**Note:** only ~1.4 GB free in normal use → idle budget of 1.2 GB in ARCHITECTURE §5 is at the limit.
Phase 0 must show what fits.

## How to reproduce
1. Build llama.cpp for Android (NDK 27, on Mac):
   ```bash
   git clone --depth 1 https://github.com/ggml-org/llama.cpp && cd llama.cpp
   cmake -S . -B build-android \
     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/27.0.12077973/build/cmake/android.toolchain.cmake \
     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
     -DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod" -DCMAKE_CXX_FLAGS="-march=armv8.2-a+dotprod" \
     -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release
   cmake --build build-android --target llama-bench -j 8
   llvm-strip build-android/bin/llama-bench   # 129 MB -> 5.4 MB
   ```
2. Put GGUF files in `models/` (git-ignored), then:
   `tools/bench/llm_bench.sh <adb-serial> <path-to-llama-bench>`

## LLM speed (Phase 0.2 — measured 2026-10-21)
llama.cpp build `0a1ef30`, CPU only, `-march=armv8.2-a+dotprod`. Prompt 128 tokens (pp), generate 64 tokens (tg), 2 runs.
Phone NOT charging, battery 82%, 35.2 °C, ~1.36 GB RAM free before test.

| Model (file) | Size | Threads | Prompt t/s | Generate t/s |
|---|---|---|---|---|
| FunctionGemma 270M Q8_0 | 272 MB | 2 | 172 | 30.6 |
| | | 4 | 205 | 30.3 |
| | | **6** | **226** | **31.8** |
| | | 8 | 244 | 30.3 |
| Qwen3 0.6B Q4_0 | 359 MB | 2 | 40 | 13.5 |
| | | 4 | 61 | 17.0 |
| | | **6** | **67** | **18.5** |
| | | 8 | 37 | 3.3 ❌ |
| Gemma 3 1B Q4_0 | 682 MB | 2 | 27 | 10.3 |
| | | 4 | 41 | 10.8 |
| | | **6** | **52** | **11.0** |
| | | 8 | 33 | 3.5 ❌ |

**What it means (simple):**
- 6 threads is best. 8 threads collapses (little cores + MIUI throttling) → never use 8.
- 4 threads is almost as fast for generation → use 4 when voice (STT/TTS) runs at the same time.
- FunctionGemma: a tool call (~30 output tokens) takes ~1 s **if the long system prompt is cached**.
  Without cache, a 600-token tool list takes ~2.7 s to read → must use KV/prefix cache of the system prompt.
- Qwen3 0.6B: ~18 words-pieces/s → speech can start in ~1–2 s. Good for chat.
- Gemma 3 1B: ~11 t/s, usable but slower and needs 682 MB. Keep as "quality" option only.
- RAM: FunctionGemma (272 MB) + Qwen3 0.6B (359 MB) ≈ 630 MB + KV cache → fits in ~1.36 GB free, barely.
- **Quality (accuracy) is NOT measured yet** — only speed. Tool-call accuracy is tested in Phase 9/13.

## Voice (Phase 0.3)
Demo APKs (sherpa-onnx, from huggingface.co/csukuangfj/sherpa-onnx-apk), files in `models/apk/` (git-ignored):
- KWS `kws-en-gigaspeech-zipformer` (1.12.22)
- ASR streaming `asr-en-small_zipformer_20M_2023_02_17` and `asr-en-zipformer2` (1.12.22)
- TTS `piper en_GB-alan-medium` (1.10.13, British male)
- Speaker ID `3dspeaker campplus_sv_en_voxceleb_16k` (1.12.22)

⚠️ KWS and both ASR demos use the same package `com.k2fsa.sherpa.onnx` → installing one replaces the other.
Test them one at a time.

### Wake word — sherpa-onnx KWS gigaspeech 3.3M (2026-10-21) ❌ FAIL
- Demo needs BPE tokens in `keywords.txt` (typing "Hey Jarvis" gives `Cannot find ID for token Hey`).
  Patched APK assets with `▁HE Y ▁JA R VI S` etc., boost `:2.0–2.5`, threshold `#0.08–0.12`, re-signed with debug key.
- Result: user said "Hey Jarvis" 30+ times → detected only a few times, only very close to the phone.
- Verdict: too weak for always-on wake word. Do not use this model for "Hey Jarvis".
- Next try: openWakeWord pretrained `hey_jarvis` model (trained only on this phrase), test on user's recorded voice.

### Wake word — openWakeWord `hey_jarvis_v0.1` (2026-10-21) ⚠️ PARTIAL
- Test: user recorded 86 s on phone (MIUI Sound Recorder, AAC → 16 kHz mono WAV):
  5x "Hey Jarvis" close, 5x at ~2 m, ~60 s normal talk. Scored on Mac (openwakeword + onnxruntime).
- Threshold 0.5: close **4/5** (scores 0.76–0.99), far **1/5**, normal talk **0 false triggers**.
- Software volume boost (x3, x6) did not help far speech (clips close speech).
- Verdict: much better than sherpa KWS; good when close, weak at 2 m. Options: lower threshold + VAD,
  openWakeWord custom verifier trained on user's voice, or train a custom model.

**"Hey Jimi" custom model (2026-10-22, D-024)** — tested on Mac (Python, same scores as phone):
| Model | Synthetic test recall @0.5 | False wakes/h (11 h val set) | rec1: 29× "Hey Jimi" | rec2: 3 min talk/TV | "Hey Jarvis" ×10 |
|---|---|---|---|---|---|
| openWakeWord auto_train (weight 1500→×2) | 26% | ~0 | 3 | 0 | 0 |
| train_fast weight 1000 | 78% | 146 | 30 (1 extra) | 3 ❌ | 4 ❌ |
| **train_fast weight 5000 (in app)** | 53% | 3.4 | **22** | **0** | **0** |
| train_fast weight 20000 | 25% | 0.2 | — | — | — |
Note: rec1/rec2 were also used in training, so real-life numbers will be lower. Feedback loop (D-023) will measure real use.

### TTS / ASR / Speaker ID
**2026-10-22, inside the app (sherpa-onnx 1.13.8 AAR, D-025):**
- STT streaming zipformer en 20M int8 (2 threads): load ~3 s; test WAVs 0.07–0.17× realtime; text correct.
  Needs ~0.5 s silence before speech, else first words are lost (same on Mac).
- Voice "alan" (Piper), sentence = 3.7–3.8 s of audio, after one warm-up call:

| Voice | 2 threads gen | 4 threads gen | load |
|---|---|---|---|
| **alan-medium fp32 (in app)** | 1.47 s | 1.25 s | 4.6 s |
| alan-low fp32 | 1.09 s | 1.29 s | 6.0 s |
| alan-medium int8 | 3.21 s | 3.07 s | 6.9 s |
| alan-low int8 | 2.73 s | 2.19 s | 7.5 s |
First call without warm-up is ~4× slower → app warms up at load. Speaker ID still pending (Phase 12).

**STT on the user's real voice (2026-10-22, D-026)** — user's "Hey Jimi" wake clips, phone timings (2 threads):
| Model | Text on user clips | rtf | load |
|---|---|---|---|
| zipformer en 20M int8 (old) | nothing on all clips | 0.1–0.2 | 3 s |
| zipformer en 2023-06-26 int8 (Mac only) | "hazy me", "has jimmy" | — | — |
| NeMo fast-conformer transducer 480ms int8 | "hi jimmy", "hi jamie" | 0.4 | 5 s |
| **Kroko zipformer en 2025-08-06 (in app)** | "hey jimmy" ×3, "state heijamin" | 0.2 | 11 s |
