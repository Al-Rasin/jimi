# Benchmarks (Phase 0)

Real measurements from the target phone. Model choices in DECISIONS.md must be based on this file.

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

_results pending — needs user to speak to the phone_
