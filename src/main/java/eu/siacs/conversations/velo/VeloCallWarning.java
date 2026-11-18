package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.siacs.conversations.R;

/**
 * Velo: call audio and video are UDP to the server's TURN relay, outside Tor. The person called
 * never sees the phone's IP, the server and the network do. Said before every outgoing call,
 * until the user taps "don't ask again".
 */
public final class VeloCallWarning {

    private static final String DONT_ASK = "velo_call_warning_dont_ask";

    private VeloCallWarning() {}

    public static void confirm(final Context context, final Runnable call) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(DONT_ASK, false)) {
            call.run();
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.velo_call_warning_title)
                .setMessage(R.string.velo_call_warning_text)
                .setPositiveButton(R.string.velo_call_warning_call, (d, w) -> call.run())
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.velo_call_warning_dont_ask, (d, w) -> {
                    prefs.edit().putBoolean(DONT_ASK, true).apply();
                    call.run();
                })
                .show();
    }
}
