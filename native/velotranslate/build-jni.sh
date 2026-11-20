#!/usr/bin/env bash
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq git cmake ninja-build build-essential python3 >/dev/null

NDK=/w/android-ndk-r26d
SRC=/w/bergamot-translator
BUILD=/w/build-arm64
cd "$BUILD"

echo "== riconfiguro con VELO_JNI=on =="
cmake -GNinja "$SRC" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_ARCH=armv8-a \
  -DUSE_MKL=off -DCOMPILE_CUDA=off -DCOMPILE_TESTS=off -DCOMPILE_UNIT_TESTS=off \
  -DCOMPILE_SERVER=off -DCOMPILE_EXAMPLES=off -DUSE_STATIC_LIBS=on \
  -DSSPLIT_USE_INTERNAL_PCRE2=on -DGIT_SUBMODULE=off -DVELO_JNI=on \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" 2>&1 | tail -6
# 20/11/2026: pagine da 16 kB, richieste da Google Play per le app nuove (Android 15+).

echo "== compilo velotranslate =="
ninja velotranslate 2>&1 | tail -30

echo "== strip e copia in /w/libvelotranslate.so =="
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o /w/libvelotranslate.so "$BUILD/libvelotranslate.so"

echo "== prodotto =="
ls -la "$BUILD/libvelotranslate.so" /w/libvelotranslate.so
echo "JNI-BUILD DONE"
