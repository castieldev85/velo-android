package eu.siacs.conversations.velo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Velo: the language and speech models this app accepts. The server only hosts them: their names
 * and SHA-256 are built into the app, as the APK's signature protects the app itself, so a server
 * taken over can't plant a file of its choosing or write outside the model folder. A new model
 * needs a new version of Velo.
 */
final class VeloKnownModels {

    static final String SPEECH_DIR = "vosk-model-small-it-0.22";
    static final String SPEECH_SHA256 =
            "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267";

    private static final Set<String> TRANSLATION_FILES =
            new HashSet<>(Arrays.asList("model.intgemm.alphas.bin", "vocab.spm", "lex.s2t.bin"));
    private static final Set<String> TRANSLATION_SHA256 =
            new HashSet<>(
                    Arrays.asList(
                            "248f47568788ecc351da7e5e07064d4153b4f71e011364ae2c931ffeec4d1cc2",
                            "21b70978ce2f3b4da7a06b5de86a09abe3acd30b9eee1b2ebb3582b9bad790bf",
                            "3ef0211d4ae6db21440892f180f2019fe2bfc110a330ffa9d2eca9665e4f2bc5",
                            "8b21914804625b2777dae7fdb636eb78f02a3eb8b7bceaa50f29ac740961da93",
                            "0700d6d70b30490e68ff9deca0b45b80310745b491855bde3a13e1692d0cbce1"));

    private VeloKnownModels() {}

    static boolean isTranslationFile(final String local, final String sha256) {
        return TRANSLATION_FILES.contains(local) && TRANSLATION_SHA256.contains(sha256);
    }

    /** A file name on the server: one path segment, no way up. */
    static boolean isServerFileName(final String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }
}
