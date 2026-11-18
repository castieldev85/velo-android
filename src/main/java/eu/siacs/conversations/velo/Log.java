package eu.siacs.conversations.velo;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

import eu.siacs.conversations.BuildConfig;

/**
 * Velo: the only way into logcat. Same calls as android.util.Log, but the release keeps the shape of
 * a message and drops what it says: accounts, addresses, links (a file link carries its key),
 * file paths, ids and stanzas. Exceptions lose their message and keep class and frames.
 */
public final class Log {

    public static final int VERBOSE = android.util.Log.VERBOSE;
    public static final int DEBUG = android.util.Log.DEBUG;
    public static final int INFO = android.util.Log.INFO;
    public static final int WARN = android.util.Log.WARN;
    public static final int ERROR = android.util.Log.ERROR;
    public static final int ASSERT = android.util.Log.ASSERT;

    private static final Pattern STANZA =
            Pattern.compile("<\\s*(message|iq|presence|stream|body|encrypted|item)\\b|xmlns\\s*=");
    private static final Pattern BODY = Pattern.compile("(?i)\\b(body|text|content|password|token)\\s*[=:].*");
    private static final Pattern LINK = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
    private static final Pattern ACCOUNT = Pattern.compile("[^\\s,;:()\\[\\]<>\"'/=]+@[^\\s,;()\\[\\]<>\"']+");
    private static final Pattern ONION = Pattern.compile("(?i)\\b[a-z2-7]{16,}\\.onion\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\b[0-9a-f]{0,4}(:[0-9a-f]{0,4}){3,}\\b");
    private static final Pattern HOST =
            Pattern.compile("(?i)\\b[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(org|com|net|eu|it|im|de|fr|es|invalid|chat|me|io)\\b");
    private static final Pattern PATH = Pattern.compile("(/[^\\s/]+){2,}/?");
    private static final Pattern DEVICES = Pattern.compile("\\[\\s*\\d+(\\s*,\\s*\\d+)*\\s*]");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9+_-]{24,}={0,2}");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d{5,}\\b");

    private Log() {}

    /** What the release writes for a message. Package-private for the tests. */
    static String clean(@Nullable final String message) {
        if (message == null) {
            return "";
        }
        if (STANZA.matcher(message).find()) {
            return "<stanza>";
        }
        String out = BODY.matcher(message).replaceAll("$1=<hidden>");
        out = LINK.matcher(out).replaceAll("<link>");
        out = ACCOUNT.matcher(out).replaceAll("<account>");
        out = ONION.matcher(out).replaceAll("<onion>");
        out = IPV4.matcher(out).replaceAll("<ip>");
        out = IPV6.matcher(out).replaceAll("<ip>");
        out = PATH.matcher(out).replaceAll("<path>");
        out = HOST.matcher(out).replaceAll("<host>");
        out = DEVICES.matcher(out).replaceAll("[<devices>]");
        out = LONG_TOKEN.matcher(out).replaceAll("<token>");
        return NUMBER.matcher(out).replaceAll("<n>");
    }

    /** Class and frames only: an exception's message may quote an address or a file name. */
    static String clean(@Nullable final Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            out.append(depth == 0 ? "" : "\nCaused by: ").append(current.getClass().getName());
            final StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 8); i++) {
                out.append("\n\tat ").append(frames[i]);
            }
        }
        return out.toString();
    }

    private static String message(@Nullable final String message) {
        return BuildConfig.DEBUG ? String.valueOf(message) : clean(message);
    }

    private static String message(@Nullable final String message, @Nullable final Throwable throwable) {
        if (BuildConfig.DEBUG) {
            return message + '\n' + android.util.Log.getStackTraceString(throwable);
        }
        return clean(message) + '\n' + clean(throwable);
    }

    public static int v(final String tag, final String msg) {
        return android.util.Log.v(tag, message(msg));
    }

    public static int v(final String tag, final String msg, final Throwable tr) {
        return android.util.Log.v(tag, message(msg, tr));
    }

    public static int d(final String tag, final String msg) {
        return android.util.Log.d(tag, message(msg));
    }

    public static int d(final String tag, final String msg, final Throwable tr) {
        return android.util.Log.d(tag, message(msg, tr));
    }

    public static int i(final String tag, final String msg) {
        return android.util.Log.i(tag, message(msg));
    }

    public static int i(final String tag, final String msg, final Throwable tr) {
        return android.util.Log.i(tag, message(msg, tr));
    }

    public static int w(final String tag, final String msg) {
        return android.util.Log.w(tag, message(msg));
    }

    public static int w(final String tag, final String msg, final Throwable tr) {
        return android.util.Log.w(tag, message(msg, tr));
    }

    public static int w(final String tag, final Throwable tr) {
        return android.util.Log.w(tag, message(null, tr));
    }

    public static int e(final String tag, final String msg) {
        return android.util.Log.e(tag, message(msg));
    }

    public static int e(final String tag, final String msg, final Throwable tr) {
        return android.util.Log.e(tag, message(msg, tr));
    }

    public static int wtf(final String tag, final String msg) {
        return android.util.Log.e(tag, message(msg));
    }

    public static int wtf(final String tag, final String msg, final Throwable tr) {
        return android.util.Log.e(tag, message(msg, tr));
    }

    public static int wtf(final String tag, final Throwable tr) {
        return android.util.Log.e(tag, message(null, tr));
    }

    public static int println(final int priority, final String tag, final String msg) {
        return android.util.Log.println(priority, tag, message(msg));
    }

    public static boolean isLoggable(final String tag, final int level) {
        return android.util.Log.isLoggable(tag, level);
    }

    public static String getStackTraceString(final Throwable tr) {
        return BuildConfig.DEBUG ? android.util.Log.getStackTraceString(tr) : clean(tr);
    }
}
