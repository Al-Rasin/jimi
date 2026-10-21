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

## LLM speed (Phase 0.2)
_pending — models downloading_

## Voice (Phase 0.3)
_pending_
