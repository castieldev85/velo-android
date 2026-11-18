package eu.siacs.conversations.velo;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import eu.siacs.conversations.velo.Log;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import eu.siacs.conversations.Config;

/**
 * Velo: Tor INCORPORATO nell'app (niente Orbot). All'avvio fa partire un Tor locale che espone il
 * proxy SOCKS su 127.0.0.1:9050 - lo stesso indirizzo che Velo usa gia' per connettersi via Tor
 * ({@code SocksSocketFactory}, {@code HttpConnectionManager}). Cosi' tutto il traffico puo' essere
 * forzato dentro Tor, sempre, senza nessun interruttore.
 *
 * <p>Fase 1: avvia Tor e segue il suo stato (STARTING/ON/OFF). Non c'e' niente da accendere a mano.
 */
public final class VeloTor {

    private static final String PORT_PREFERENCES = "velo_tor";
    private static final String PORT_KEY = "socks_port";
    private static final int FIRST_PORT = 20_000;
    private static final int PORT_RANGE = 40_000;
    private static final long MAX_LOG_BYTES = 64 * 1024;
    private static final long MIN_RESTART_INTERVAL_MS = 60_000;
    private static final String TOR_PROCESS_SUFFIX = ":tor";

    private static volatile boolean ready;
    private static volatile String lastStatus; // ultimo stato ricevuto da TorService (diagnostica)
    private static boolean started;
    private static BroadcastReceiver receiver;
    private static volatile boolean torDown; // il processo :tor e' morto o non risponde alla porta
    private static long lastRestart;
    // Velo 0.38.1, per la Diagnostica: quando e' partito Tor (0 = era gia' acceso) e quando e'
    // diventato pronto; quanti circuiti ha aperti (-1 = non si sa), letto dal canale di controllo.
    private static volatile long startedAt;
    private static volatile long readyAt;
    private static volatile int circuits = -1;
    private static volatile long circuitsAskedAt;
    private static volatile long bootstrapAskedAt;

    // Velo: il legame dal servizio in primo piano alza la priorita' di :tor e fa ricreare Tor da
    // Android se il processo muore. Dice anche se Tor e' vivo: il log da solo resta a 100%.
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            torDown = false;
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            torDown = true;
            ready = false;
        }
    };

    private VeloTor() {}

    private static volatile int socksPort;

    /**
     * Velo 0.31: la porta di Tor e' casuale e diversa per ogni installazione: un'altra app non puo'
     * occupare in anticipo una porta nota per ricevere le connessioni di Velo.
     */
    public static int socksPort() {
        int port = socksPort;
        if (port != 0) {
            return port;
        }
        synchronized (VeloTor.class) {
            if (socksPort == 0) {
                final android.content.SharedPreferences preferences =
                        eu.siacs.conversations.Conversations.getContext()
                                .getSharedPreferences(PORT_PREFERENCES, Context.MODE_PRIVATE);
                port = preferences.getInt(PORT_KEY, 0);
                if (port == 0) {
                    port = newPort(preferences);
                }
                socksPort = port;
            }
            return socksPort;
        }
    }

    private static int newPort(final android.content.SharedPreferences preferences) {
        final int port = FIRST_PORT + new java.security.SecureRandom().nextInt(PORT_RANGE);
        preferences.edit().putInt(PORT_KEY, port).commit();
        return port;
    }

    /** true se il processo :tor e' morto o non risponde alla porta. */
    public static boolean isDown() {
        return torDown;
    }

    /** true nel processo isolato dei documenti (DocService). */
    public static boolean isIsolatedProcess() {
        final int appId = Process.myUid() % 100_000;
        return appId >= 99_000 && appId <= 99_999;
    }

    /** Ultima percentuale di bootstrap letta dal log di Tor, o -1. Imposta anche {@link #ready}. */
    private static int bootstrapPercent(final Context context) {
        final String log = torLog(context);
        if (log == null) {
            return -1;
        }
        int pct = -1;
        final java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("Bootstrapped (\\d+)%").matcher(log);
        while (m.find()) {
            try {
                pct = Integer.parseInt(m.group(1));
            } catch (final NumberFormatException ignored) {
            }
        }
        // prima di start() il log puo' essere quello di un giro precedente: non vale
        if (pct >= 100 && started) {
            markReady();
        }
        return pct;
    }

    private static void markReady() {
        if (!ready) {
            readyAt = SystemClock.elapsedRealtime();
        }
        ready = true;
    }

    /** Millisecondi che Tor ha impiegato ad avviarsi, o -1 se non si sa (era gia' acceso). */
    public static long startupMs() {
        final long begin = startedAt;
        final long end = readyAt;
        return begin == 0 || end < begin ? -1 : end - begin;
    }

    /** Millisecondi da quando Tor e' pronto, o -1. */
    public static long readyForMs() {
        final long since = readyAt;
        return !ready || since == 0 ? -1 : SystemClock.elapsedRealtime() - since;
    }

    /**
     * Quanti circuiti Tor ha pronti (stato BUILT), o -1. Il valore arriva dall'ultima domanda al
     * canale di controllo di Tor; se e' vecchia di oltre 3 s ne parte un'altra in background.
     * Si conta soltanto: i nodi non vengono letti, mostrati, scritti nel log ne' salvati.
     */
    public static int circuits(final Context context) {
        final long now = SystemClock.elapsedRealtime();
        if (now - circuitsAskedAt > 3000) {
            circuitsAskedAt = now;
            final Context app = context.getApplicationContext();
            new Thread(() -> circuits = countCircuits(app), "velo-tor-circuits").start();
        }
        return circuits;
    }

    private static int countCircuits(final Context app) {
        if (!ready || torDown) {
            return -1;
        }
        final File socket = new File(app.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket");
        try (final android.net.LocalSocket s = new android.net.LocalSocket()) {
            s.connect(new android.net.LocalSocketAddress(
                    socket.getAbsolutePath(), android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            s.setSoTimeout(3000);
            s.getOutputStream().write(
                    "AUTHENTICATE\r\nGETINFO circuit-status\r\nQUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            final java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            final java.util.regex.Pattern built =
                    java.util.regex.Pattern.compile("^(250-circuit-status=)?\\d+ BUILT ");
            int count = 0;
            String line;
            while ((line = in.readLine()) != null) {
                // errore di Tor: codice 5xx (non un circuito con numero che inizia per 5)
                if (line.length() >= 4 && line.charAt(0) == '5'
                        && Character.isDigit(line.charAt(1)) && Character.isDigit(line.charAt(2))
                        && (line.charAt(3) == ' ' || line.charAt(3) == '-')) {
                    Log.d(Config.LOGTAG, "VeloTor: circuiti non letti, risposta " + line.substring(0, 3));
                    return -1;
                }
                if (built.matcher(line).find()) {
                    count++;
                }
            }
            return count;
        } catch (final Throwable t) {
            Log.d(Config.LOGTAG, "VeloTor: circuiti non letti (" + t.getClass().getSimpleName() + ")");
            return -1;
        }
    }

    /** true quando Tor ha finito di avviarsi (100%) ed e' pronto a instradare le connessioni. */
    public static boolean isReady(final Context context) {
        if (torDown) {
            return false;
        }
        if (ready) {
            return true;
        }
        if (bootstrapPercent(context) >= 100) {
            return true;
        }
        askBootstrap(context);
        return ready;
    }

    /**
     * Velo 0.42.2: asks Tor itself whether it finished starting. The log alone was not enough: when
     * Android restarts the app while :tor stays alive, Tor does not write "Bootstrapped 100%" again,
     * and on a phone on for hours that line is no longer in the last 8 KB read. Velo then took Tor
     * for not ready forever: yellow icon with the chats working, and every connection waiting first.
     * In the background, at most every 3 s; only the bootstrap state is read.
     */
    private static void askBootstrap(final Context context) {
        if (!started || torDown) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now - bootstrapAskedAt < 3000) {
            return;
        }
        bootstrapAskedAt = now;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            if (bootstrapDone(app)) {
                Log.d(Config.LOGTAG, "VeloTor: Tor pronto (dal canale di controllo)");
                markReady();
            }
        }, "velo-tor-bootstrap").start();
    }

    private static boolean bootstrapDone(final Context app) {
        final File socket = new File(app.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket");
        if (!socket.exists()) {
            return false;
        }
        try (final android.net.LocalSocket s = new android.net.LocalSocket()) {
            s.connect(new android.net.LocalSocketAddress(
                    socket.getAbsolutePath(), android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            s.setSoTimeout(3000);
            s.getOutputStream().write(
                    "AUTHENTICATE\r\nGETINFO status/bootstrap-phase\r\nQUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            final java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("250-status/bootstrap-phase=")) {
                    return line.contains(" PROGRESS=100 ");
                }
            }
            return false;
        } catch (final Throwable t) {
            Log.d(Config.LOGTAG, "VeloTor: stato di avvio non letto (" + t.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /**
     * Velo 0.38: aspetta che Tor sia pronto, al massimo {@code timeoutMs}. Collegarsi prima lascia
     * la richiesta SOCKS appesa fino al timeout (90 s) e poi ancora l'attesa del nuovo tentativo:
     * dopo un aggiornamento il collegamento arrivava in oltre 2 minuti. Dopo il tempo massimo si
     * prova comunque, come prima.
     */
    public static void awaitReady(final Context context, final long timeoutMs)
            throws InterruptedException {
        if (!started || isReady(context)) {
            return;
        }
        Log.d(Config.LOGTAG, "VeloTor: attendo che Tor sia pronto prima di collegarmi");
        final long begin = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - begin < timeoutMs) {
            Thread.sleep(500);
            if (isReady(context)) {
                Log.d(Config.LOGTAG, "VeloTor: Tor pronto dopo " + (SystemClock.elapsedRealtime() - begin) + " ms");
                return;
            }
        }
        Log.d(Config.LOGTAG, "VeloTor: Tor non pronto dopo " + timeoutMs + " ms, provo comunque");
    }

    /** true se il processo :tor di questa app e' gia' in esecuzione. */
    private static boolean torProcessAlive(final Context app) {
        final ActivityManager am = app.getSystemService(ActivityManager.class);
        final String torProcess = app.getPackageName() + TOR_PROCESS_SUFFIX;
        if (am != null && am.getRunningAppProcesses() != null) {
            for (final ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (torProcess.equals(info.processName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Testo di stato per la diagnostica: a che punto è il bootstrap di Tor. */
    public static String statusText(final Context context) {
        if (!started) {
            return "non avviato";
        }
        if (torDown) {
            return "fermo, riavvio in corso…";
        }
        final int pct = bootstrapPercent(context);
        if (pct >= 100 || ready) {
            return "pronto ✓ (100%)";
        }
        if (pct >= 0) {
            return "in avvio… (" + pct + "%)";
        }
        if (lastStatus == null) {
            return "in avvio… (nessun segnale ancora)";
        }
        return "in avvio… (" + lastStatus + ")";
    }

    /**
     * Avvia il Tor interno una sola volta. Da chiamare all'avvio del servizio principale.
     * TUTTO e' avvolto in try/catch: un problema di Tor non deve MAI chiudere l'app. Inoltre
     * {@code TorService} gira in un processo isolato ({@code :tor}, vedi manifest), quindi anche
     * un crash interno di Tor non tocca il processo principale.
     */
    public static synchronized void start(final Context context) {
        if (started) {
            return;
        }
        final Context app = context.getApplicationContext();
        try {
            // Velo 0.38: con Tor spento il log contiene ancora il "Bootstrapped 100%" del giro
            // prima, e il primo collegamento (che chiede isReady() subito) partirebbe troppo presto.
            // Se :tor e' ancora vivo il log resta: non riscriverebbe la riga Bootstrapped.
            if (!torProcessAlive(app)) {
                noticeLog(app).delete();
                ready = false; // l'indicatore di stato puo' averlo letto prima di questo avvio
                startedAt = SystemClock.elapsedRealtime();
            }
        } catch (final Throwable t) {
            Log.d(Config.LOGTAG, "VeloTor: log di Tor non azzerato", t);
        }
        started = true;
        new Thread(() -> {
            try {
                writeTorrc(app);
                receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(final Context ctx, final Intent intent) {
                        final String status = intent.getStringExtra(TorService.EXTRA_STATUS);
                        if (status == null) {
                            return;
                        }
                        lastStatus = status;
                        switch (status) {
                            case TorService.STATUS_ON:
                                markReady();
                                Log.d(Config.LOGTAG, "VeloTor: Tor pronto");
                                break;
                            case TorService.STATUS_OFF:
                            case TorService.STATUS_STOPPING:
                                ready = false;
                                break;
                            default:
                                break;
                        }
                    }
                };
                final IntentFilter filter = new IntentFilter(TorService.ACTION_STATUS);
                // il broadcast arriva solo dal nostro stesso pacchetto
                androidx.core.content.ContextCompat.registerReceiver(
                        app, receiver, filter,
                        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
                TorService.setBroadcastPackageName(app.getPackageName());
                // IMPORTANTE: startService (NON startForegroundService): TorService e' un servizio
                // normale e non si promuove a primo piano -> startForegroundService lo farebbe
                // crashare ("did not then call startForeground"). Era la causa del crash 0.22.19.
                final Intent intent = new Intent(app, TorService.class);
                intent.setAction(TorService.ACTION_START);
                app.startService(intent);
                app.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                Log.d(Config.LOGTAG, "VeloTor: avvio Tor interno…");
            } catch (final Throwable t) {
                Log.e(Config.LOGTAG, "VeloTor: avvio Tor fallito (l'app continua normale)", t);
            }
        }, "velo-tor-start").start();
    }

    /**
     * La porta SOCKS non risponde: riavvia Tor. Chiude il processo :tor se e' ancora vivo (il
     * legame terrebbe in piedi il servizio anche con Tor fermo dentro) e lo fa ripartire. Al
     * massimo una volta al minuto, per non interrompere un avvio in corso.
     */
    public static synchronized void restart(final Context context) {
        if (!started) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (lastRestart != 0 && now - lastRestart < MIN_RESTART_INTERVAL_MS) {
            return;
        }
        lastRestart = now;
        torDown = true;
        ready = false;
        startedAt = now;
        readyAt = 0;
        try {
            final Context app = context.getApplicationContext();
            final ActivityManager am = app.getSystemService(ActivityManager.class);
            final String torProcess = app.getPackageName() + TOR_PROCESS_SUFFIX;
            if (am != null && am.getRunningAppProcesses() != null) {
                for (final ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                    if (torProcess.equals(info.processName)) {
                        Process.killProcess(info.pid);
                    }
                }
            }
            // Velo 0.31: Tor non risponde su questa porta, che potrebbe averla presa qualcun altro
            socksPort = newPort(app.getSharedPreferences(PORT_PREFERENCES, Context.MODE_PRIVATE));
            noticeLog(app).delete(); // Tor riparte: il vecchio 100% non vale piu'
            writeTorrc(app);
            final Intent intent = new Intent(app, TorService.class);
            intent.setAction(TorService.ACTION_START);
            app.startService(intent);
            Log.d(Config.LOGTAG, "VeloTor: porta SOCKS assente, riavvio Tor");
        } catch (final Throwable t) {
            Log.e(Config.LOGTAG, "VeloTor: riavvio Tor fallito", t);
        }
    }

    /** File dove Tor scrive il suo log (diagnostica: a che punto è il bootstrap, errori). */
    private static File noticeLog(final Context app) {
        return new File(app.getFilesDir(), "velo-tor-notice.log");
    }

    /** Ultime righe del log di Tor (per la Diagnostica), o null. */
    public static String torLog(final Context context) {
        try {
            final File f = noticeLog(context.getApplicationContext());
            if (!f.isFile() || f.length() == 0) {
                return null;
            }
            // Velo: finestra piu' ampia (8 KB) per non far scorrere via l'errore onion con il
            // log a info.
            final long skip = Math.max(0, f.length() - 8000);
            try (final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(skip);
                final byte[] buf = new byte[(int) (f.length() - skip)];
                raf.readFully(buf);
                return new String(buf, StandardCharsets.UTF_8);
            }
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Scrive (una volta) il torrc: porta SOCKS 9050 + log di Tor su file per la diagnostica. */
    private static void writeTorrc(final Context app) throws Exception {
        final File torrc = TorService.getTorrc(app);
        if (torrc == null) {
            return;
        }
        final File parent = torrc.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        // Velo: via il log a info della 0.22.36 (nodi Tor sul telefono, file senza limite). Non si
        // azzera a ogni avvio: se il processo :tor e' ancora vivo si perderebbe la riga Bootstrapped.
        final File log = noticeLog(app);
        final String tail = torLog(app);
        if (log.length() > MAX_LOG_BYTES || (tail != null && tail.contains("[info]"))) {
            log.delete();
        }
        final String conf =
                "SocksPort " + socksPort() + "\n"
                        + "Log notice file " + log.getAbsolutePath() + "\n"
                        + "# Velo: Tor incorporato, sempre attivo\n";
        try (final OutputStream os = new FileOutputStream(torrc, false)) {
            os.write(conf.getBytes(StandardCharsets.UTF_8));
        }
    }
}
