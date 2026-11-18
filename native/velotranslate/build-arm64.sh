#!/usr/bin/env bash
# Fase 1 — compila Bergamot per Android arm64. Gira nel container, /w montato.
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq git cmake ninja-build python3 build-essential wget unzip ccache pkg-config >/dev/null

NDK=/w/android-ndk-r26d
SRC=/w/bergamot-translator
BUILD=/w/build-arm64
mkdir -p "$BUILD"; cd "$BUILD"

echo "== configurazione CMake (Android arm64) =="
cmake -GNinja "$SRC" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_ARCH=armv8-a \
  -DUSE_MKL=off \
  -DCOMPILE_CUDA=off \
  -DCOMPILE_TESTS=off \
  -DCOMPILE_UNIT_TESTS=off \
  -DCOMPILE_SERVER=off \
  -DCOMPILE_EXAMPLES=off \
  -DUSE_STATIC_LIBS=on \
  -DSSPLIT_USE_INTERNAL_PCRE2=on \
  2>&1 | tail -25

echo "== target disponibili =="
ninja -t targets | grep -i -E 'bergamot|libbergamot|\.so|\.a' | head -20 || true

echo "== compilazione (puo' richiedere parecchio) =="
ninja 2>&1 | tail -40

echo "== prodotti =="
find "$BUILD" -name '*.so' -o -name 'libbergamot*' -o -name 'bergamot' -type f 2>/dev/null | head
echo "BUILD-ARM64 DONE"
