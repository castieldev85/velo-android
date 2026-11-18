package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Velo: how many times the lock screen may be tried. After a few wrong PINs each new try waits
 * longer, across restarts of the app. The panic PIN is never made to wait: it may be typed under
 * pressure, at any moment.
 */
public final class VeloPinGuard {

    public static final int MIN_LENGTH = 6;

    private static final String PREFERENCES = "velo_pin_guard";
    private static final String FAILURES = "failures";
    private static final String LAST_FAILURE = "last_failure";
    private static final int FREE_TRIES = 5;
    private static final long FIRST_WAIT_MS = 30_000;
    private static final long LONGEST_WAIT_MS = 15 * 60_000;

    private VeloPinGuard() {}

    static long waitAfter(final int failures) {
        if (failures < FREE_TRIES) {
            return 0;
        }
        final int doublings = Math.min(failures - FREE_TRIES, 10);
        return Math.min(FIRST_WAIT_MS << doublings, LONGEST_WAIT_MS);
    }

    /** How long until the next try counts, in milliseconds. A clock moved back buys no time. */
    public static long remainingMs(final Context context) {
        final SharedPreferences preferences = preferences(context);
        final long wait = waitAfter(preferences.getInt(FAILURES, 0));
        final long last = preferences.getLong(LAST_FAILURE, 0);
        final long now = System.currentTimeMillis();
        if (wait == 0) {
            return 0;
        }
        return now < last ? wait : Math.max(0, last + wait - now);
    }

    public static void failed(final Context context) {
        final SharedPreferences preferences = preferences(context);
        preferences
                .edit()
                .putInt(FAILURES, preferences.getInt(FAILURES, 0) + 1)
                .putLong(LAST_FAILURE, System.currentTimeMillis())
                .commit();
    }

    public static void reset(final Context context) {
        preferences(context).edit().clear().commit();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
