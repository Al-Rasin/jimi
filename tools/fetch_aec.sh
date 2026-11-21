#!/usr/bin/env bash
# Builds the echo canceler (D-103): WebRTC AEC3 from webrtc-audio-processing (pinned commit) + our JNI wrapper
# app/src/main/cpp/aec/jimi_aec.cpp -> app/src/main/jniLibs/arm64-v8a/libjimi_aec.so (git-ignored, ~1.2 MB).
# Without the .so the app still runs; barge-in "stop" then works only on quiet playback.
# Needs: Android NDK 27 (same as llama.cpp), meson, ninja (brew install meson ninja).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
WORK=${AEC_WORK:-$ROOT/build/aec}
COMMIT=d0569cf
NDK=${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/27.0.12077973}
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)
OUT=$ROOT/app/src/main/jniLibs/arm64-v8a/libjimi_aec.so

mkdir -p "$WORK"
if [ ! -d "$WORK/src/.git" ]; then
  git clone https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git "$WORK/src"
fi
git -C "$WORK/src" fetch --quiet origin || true
git -C "$WORK/src" checkout --quiet "$COMMIT"

cat > "$WORK/android.ini" <<EOF
[binaries]
c = '$BIN/aarch64-linux-android30-clang'
cpp = '$BIN/aarch64-linux-android30-clang++'
ar = '$BIN/llvm-ar'
strip = '$BIN/llvm-strip'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
[built-in options]
cpp_args = ['-fPIC','-ffunction-sections','-fdata-sections']
c_args = ['-fPIC','-ffunction-sections','-fdata-sections']
EOF

B=$WORK/build
if [ ! -f "$B/build.ninja" ]; then
  (cd "$WORK/src" && meson setup "$B" --cross-file "$WORK/android.ini" --buildtype=release \
    -Ddefault_library=static --wrap-mode=forcefallback)
fi
ninja -C "$B" >/dev/null

ABSL=$(ls -d "$B"/subprojects/abseil-cpp-*)
LIBS="$B/webrtc/modules/audio_processing/libwebrtc-audio-processing-2.a
  $(find "$B/webrtc" -name '*.a' ! -name 'libwebrtc-audio-processing-2.a') $(ls "$ABSL"/libabsl_*.a)"
INC="-I$B -I$WORK/src -I$WORK/src/webrtc -I$ABSL"
for d in "$WORK"/src/subprojects/abseil-cpp-*; do [ -d "$d" ] && INC="$INC -I$d"; done
mkdir -p "$(dirname "$OUT")"
# shellcheck disable=SC2086
"$BIN/aarch64-linux-android30-clang++" -std=c++17 -O2 -shared -fPIC -ffunction-sections -fdata-sections \
  -DNDEBUG -DWEBRTC_POSIX -DWEBRTC_ANDROID -DWEBRTC_LINUX -DWEBRTC_ARCH_ARM64 -DWEBRTC_APM_DEBUG_DUMP=0 \
  $INC app/src/main/cpp/aec/jimi_aec.cpp -Wl,--start-group $LIBS -Wl,--end-group \
  -static-libstdc++ -llog -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL -o "$OUT"
"$BIN/llvm-strip" "$OUT"
echo "built $OUT ($(wc -c < "$OUT") bytes)"
