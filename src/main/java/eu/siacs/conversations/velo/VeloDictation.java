package eu.siacs.conversations.velo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import eu.siacs.conversations.entities.Account;

/**
 * Velo: dettatura vocale OFFLINE (Vosk). Scarica/carica il modello della lingua (tramite
 * {@link VeloSpeechManager}), ascolta dal microfono e restituisce il testo trascritto, tutto sul
 * telefono (niente audio esce, niente Google). I callback {@link Ui} arrivano sul thread principale.
 */
public final class VeloDictation implements RecognitionListener {

    public enum State { PREPARING, LISTENING, STOPPED }

    public interface Ui {
        /** Sta aprendo il modello: un momento. */
        void onPreparing();
        /** Scarica il modello, solo la prima volta. */
        void onDownload(long done, long total);
        /** Scaricato, lo sta scompattando. */
        void onUnpacking();
        /** Ora sta ascoltando. */
        void onListening();
        /** Trascrizione parziale in corso (cambia mentre parli). */
        void onPartial(String text);
        /** Un pezzo di frase e' stato chiuso (a una pausa). */
        void onResult(String text);
        /** Dettatura terminata, ultimo pezzo. */
        void onFinal(String text);
        /** Qualcosa e' andato storto (testo gia' tradotto in italiano). */
        void onError(String message);
    }

    private static final float SAMPLE_RATE = 16000.0f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speech;
    private volatile boolean active;
    private volatile boolean releasing;
    private volatile boolean cancelled;
    private Thread loader;

    public boolean isActive() {
        return active;
    }

    /** Avvia la dettatura: scarica/carica il modello e poi ascolta. Da chiamare sul thread UI. */
    public void start(final Context context, final Account account, final String lang, final Ui ui) {
        if (active) {
            return;
        }
        this.ui = ui;
        this.active = true;
        ui.onPreparing();
        loader = new Thread(() -> {
            try {
                final Context app = context.getApplicationContext();
                final VeloSpeechManager.Progress progress = new VeloSpeechManager.Progress() {
                    @Override
                    public void onDownload(final long done, final long total) {
                        main.post(() -> {
                            if (active && VeloDictation.this.ui != null) {
                                VeloDictation.this.ui.onDownload(done, total);
                            }
                        });
                    }

                    @Override
                    public void onUnpacking() {
                        main.post(() -> {
                            if (active && VeloDictation.this.ui != null) {
                                VeloDictation.this.ui.onUnpacking();
                            }
                        });
                    }

                    @Override
                    public boolean wanted() {
                        return !cancelled;
                    }
                };
                final java.io.File dir = VeloSpeechManager.ensureModel(app, account, lang, progress);
                Model m;
                try {
                    m = new Model(dir.getAbsolutePath());
                } catch (final java.io.IOException unreadable) {
                    // Velo 0.29.1: un modello rovinato sul telefono non blocca per sempre: si riscarica una volta
                    VeloSpeechManager.discard(app);
                    m = new Model(VeloSpeechManager.ensureModel(app, account, lang, progress).getAbsolutePath());
                }
                final Model model = m;
                main.post(() -> startListening(model));
            } catch (final VeloSpeechManager.Cancelled e) {
                active = false;
            } catch (final Throwable t) {
                active = false;
                main.post(() -> {
                    if (this.ui != null) {
                        this.ui.onError(t.getMessage() == null ? String.valueOf(t) : t.getMessage());
                    }
                });
            }
        });
        loader.start();
    }

    private void startListening(final Model m) {
        if (!active) {
            // annullata mentre scaricava
            m.close();
            return;
        }
        try {
            this.model = m;
            this.recognizer = new Recognizer(m, SAMPLE_RATE);
            this.speech = new SpeechService(this.recognizer, SAMPLE_RATE);
            this.speech.startListening(this);
            if (ui != null) {
                ui.onListening();
            }
        } catch (final Throwable t) {
            active = false;
            finish();
            if (ui != null) {
                ui.onError(t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            }
        }
    }

    /** Ferma la dettatura (consegna l'ultimo pezzo via onFinal) e rilascia. Dal thread UI. */
    public void stop() {
        if (!active) {
            return;
        }
        active = false;
        if (speech == null && loader != null) {
            // stava ancora scaricando/caricando: nessun motore da fermare, e il download si interrompe
            cancelled = true;
            if (ui != null) {
                ui.onFinal("");
            }
            return;
        }
        finish();
    }

    /** Rilascia tutto senza consegnare testo (invio del messaggio / chiusura schermata). Dal thread UI. */
    public void release() {
        active = false;
        ui = null;
        finish();
    }

    /**
     * Ferma il thread di Vosk (con join) e chiude il motore, su un thread in background. IMPORTANTE:
     * si usa {@code speech.stop()} (interrupt + join), NON {@code shutdown()} che rilascia solo il
     * microfono lasciando VIVO il thread di riconoscimento: chiudere il modello nativo mentre il
     * thread gira causa un crash nativo (era il crash premendo Invio per inviare). Idempotente.
     */
    private synchronized void finish() {
        if (releasing) {
            return;
        }
        releasing = true;
        final SpeechService s = speech;
        final Recognizer r = recognizer;
        final Model m = model;
        speech = null;
        recognizer = null;
        model = null;
        new Thread(() -> {
            try { if (s != null) s.stop(); } catch (final Throwable ignored) {}   // join del thread
            try { if (r != null) r.close(); } catch (final Throwable ignored) {}
            try { if (m != null) m.close(); } catch (final Throwable ignored) {}
            releasing = false;
        }, "velo-dict-finish").start();
    }

    private static String textOf(final String json, final String field) {
        if (json == null) {
            return "";
        }
        try {
            return new JSONObject(json).optString(field, "").trim();
        } catch (final org.json.JSONException e) {
            return "";
        }
    }

    // --- RecognitionListener (sul thread principale) ---

    @Override
    public void onPartialResult(final String hypothesis) {
        if (ui != null) {
            ui.onPartial(textOf(hypothesis, "partial"));
        }
    }

    @Override
    public void onResult(final String hypothesis) {
        if (ui != null) {
            final String t = textOf(hypothesis, "text");
            if (!t.isEmpty()) {
                ui.onResult(t);
            }
        }
    }

    @Override
    public void onFinalResult(final String hypothesis) {
        // NIENTE chiusura del motore qui: la fa finish() dopo il join del thread. Qui solo il testo.
        if (ui != null) {
            ui.onFinal(textOf(hypothesis, "text"));
        }
    }

    @Override
    public void onError(final Exception e) {
        active = false;
        finish();
        if (ui != null) {
            ui.onError(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    @Override
    public void onTimeout() {
        stop();
    }
}
