package eu.siacs.conversations.velo;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Velo: cattura l'ULTIMO crash non gestito (di qualsiasi processo dell'app, incluso il processo
 * isolato ":tor") scrivendolo su un file, così lo possiamo mostrare in Diagnostica e capire perché
 * Tor si chiude, senza dover collegare il telefono al PC. Tutto difensivo: non deve mai a sua volta
 * far crashare l'app.
 */
public final class VeloCrashLog {

    private static final String FILE = "velo-crash.log";

    private VeloCrashLog() {}

    /** Da chiamare all'avvio (in {@code Application.onCreate}, che gira in ogni processo). */
    public static void install(final Context context) {
        try {
            final Context app = context.getApplicationContext();
            final File file = new File(app.getFilesDir(), FILE);
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    final StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    final String entry =
                            new java.util.Date().toString()
                                    + "  processo=" + processName()
                                    + "  thread=" + thread.getName() + "\n"
                                    + sw + "\n";
                    // sovrascrive: teniamo solo l'ultimo crash (quello che ci interessa)
                    try (final OutputStream os = new FileOutputStream(file, false)) {
                        os.write(entry.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (final Throwable ignored) {
                    // non deve mai peggiorare le cose
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            });
        } catch (final Throwable ignored) {
        }
    }

    /** L'ultimo crash registrato (troncato), oppure null se non ce n'è. */
    public static String last(final Context context) {
        try {
            final File file = new File(context.getApplicationContext().getFilesDir(), FILE);
            if (!file.isFile() || file.length() == 0) {
                return null;
            }
            final byte[] buf = new byte[(int) Math.min(file.length(), 3000)];
            try (final java.io.InputStream in = new java.io.FileInputStream(file)) {
                final int n = in.read(buf);
                if (n <= 0) {
                    return null;
                }
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            }
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Cancella l'ultimo crash registrato. */
    public static void clear(final Context context) {
        try {
            new File(context.getApplicationContext().getFilesDir(), FILE).delete();
        } catch (final Throwable ignored) {
        }
    }

    private static String processName() {
        try {
            return android.app.Application.getProcessName();
        } catch (final Throwable ignored) {
        }
        try {
            final byte[] b = new byte[128];
            try (final java.io.InputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
                final int n = in.read(b);
                if (n > 0) {
                    return new String(b, 0, n, StandardCharsets.UTF_8).trim().replace("\0", "");
                }
            }
        } catch (final Throwable ignored) {
        }
        return "?";
    }
}
