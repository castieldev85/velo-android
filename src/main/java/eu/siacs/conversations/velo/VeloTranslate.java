package eu.siacs.conversations.velo;

/**
 * Velo: traduzione dei messaggi OFFLINE, senza Google. Ponte verso il motore Bergamot
 * (Mozilla) compilato per Android in {@code libvelotranslate.so}. Il modello di lingua
 * (it↔en ecc.) non sta nell'APK: si scarica a richiesta e vive sul telefono; qui si passa
 * il percorso di un {@code config.yml} che punta a modello, vocabolario e shortlist.
 */
public final class VeloTranslate {

    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("velotranslate");
            ok = true;
        } catch (final Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    private VeloTranslate() {}

    /** true se la libreria nativa è stata caricata (presente per questa architettura). */
    public static boolean isAvailable() {
        return LOADED;
    }

    /**
     * Carica il modello indicato dal config e traduce il testo, in una sola chiamata sincrona.
     * Ritorna la traduzione, oppure una stringa che inizia con "[ERR] " in caso di problema
     * (non lancia e non fa chiudere l'app). Da chiamare fuori dal thread principale.
     */
    public static native String nativeTranslateOnce(String configPath, String text);
}
