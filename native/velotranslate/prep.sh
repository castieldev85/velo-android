#!/usr/bin/env bash
# Fase 1 — preparazione: strumenti, NDK Linux, clone bergamot con sotto-moduli.
# Gira DENTRO il container Linux, con /w montato sulla cartella velo-bergamot.
set -e
cd /w
echo "== [1/4] strumenti build =="
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq git cmake ninja-build python3 build-essential wget unzip ccache pkg-config >/dev/null
echo "  cmake $(cmake --version | head -1), ninja $(ninja --version)"

echo "== [2/4] NDK Linux r26d (se manca) =="
NDK_ZIP=android-ndk-r26d-linux.zip
if [ ! -d /w/android-ndk-r26d ]; then
  [ -f /w/$NDK_ZIP ] || wget -q "https://dl.google.com/android/repository/$NDK_ZIP" -O /w/$NDK_ZIP
  echo "  estraggo NDK..."
  unzip -q -o /w/$NDK_ZIP -d /w
fi
echo "  NDK: $(ls -d /w/android-ndk-r26d 2>/dev/null)"

echo "== [3/4] clone bergamot-translator + sotto-moduli (shallow) =="
if [ ! -d /w/bergamot-translator/.git ]; then
  git clone --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/browsermt/bergamot-translator.git /w/bergamot-translator 2>&1 | tail -3
else
  echo "  gia' clonato"
fi

echo "== [4/4] riepilogo =="
du -sh /w/bergamot-translator 2>/dev/null
ls /w/bergamot-translator/3rd_party
echo "PREP OK"
