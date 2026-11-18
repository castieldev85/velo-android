package eu.siacs.conversations.velo;

import android.content.Context;

import eu.siacs.conversations.R;

/**
 * Velo: exact durations for the message timer ("5 s", "10 min", "1 g"). the upstream project's readable
 * ephemeral duration rounds everything under a minute to "30 seconds".
 */
public final class VeloDurations {

    private VeloDurations() {}

    public static String format(final Context context, final int seconds) {
        if (seconds % 86400 == 0) {
            return context.getString(R.string.velo_unit_days, seconds / 86400);
        } else if (seconds % 3600 == 0) {
            return context.getString(R.string.velo_unit_hours, seconds / 3600);
        } else if (seconds % 60 == 0) {
            return context.getString(R.string.velo_unit_minutes, seconds / 60);
        }
        return context.getString(R.string.velo_unit_seconds, seconds);
    }
}
