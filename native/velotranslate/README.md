# libvelotranslate.so (traduzione offline di Velo)

Sorgente della libreria `src/main/jniLibs/arm64-v8a/libvelotranslate.so`: ponte JNI
(`velotranslate.cpp`) collegato a bergamot-translator (Mozilla, MPL-2.0).

1. Scarica bergamot-translator ai commit in `UPSTREAM-COMMITS.txt` (con i sottomoduli).
2. Applica `bergamot-CMakeLists.patch` (aggiunge il target `velotranslate`) e
   `marian-dev.patch` dentro `3rd_party/marian-dev`; copia `velotranslate.cpp` in `velo-jni/`.
3. Compila per Android arm64 con l'NDK r26d in un Linux (anche Docker): `prep.sh`,
   `build-arm64.sh`, poi `build-jni.sh` (opzione CMake `-DVELO_JNI=on`).
I modelli di traduzione non sono qui: l'app li scarica al primo uso.
