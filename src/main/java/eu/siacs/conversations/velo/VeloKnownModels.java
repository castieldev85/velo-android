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

    /**
     * Velo 0.52: one Vosk model per speech language, {lang, folder, SHA-256 of the ZIP}. All
     * "small" models from alphacephei.com, Apache-2.0.
     */
    private static final String[][] SPEECH = {
        {"it", "vosk-model-small-it-0.22",
            "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267"},
        {"en", "vosk-model-small-en-us-0.15",
            "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"},
        {"es", "vosk-model-small-es-0.42",
            "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"},
        {"fr", "vosk-model-small-fr-0.22",
            "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad"},
    };

    /** The model folder for a speech language, or null when Velo has no model for it. */
    static String speechDir(final String lang) {
        for (final String[] m : SPEECH) {
            if (m[0].equals(lang)) {
                return m[1];
            }
        }
        return null;
    }

    /** The SHA-256 of that language's ZIP, or null. */
    static String speechSha256(final String lang) {
        for (final String[] m : SPEECH) {
            if (m[0].equals(lang)) {
                return m[2];
            }
        }
        return null;
    }

    /** true for the folder of one of the models above (anything else in the model folder is old). */
    static boolean isSpeechDir(final String dir) {
        for (final String[] m : SPEECH) {
            if (m[1].equals(dir)) {
                return true;
            }
        }
        return false;
    }

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
