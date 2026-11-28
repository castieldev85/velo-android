package eu.siacs.conversations.velo;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.XmppConnectionService;

/**
 * Velo 0.38: after an update, the first time the chat list opens, a window lists what changed
 * (R.string.velo_whats_new_text, rewritten for every release). It never shows on a fresh install,
 * nor on an empty Velo (after the panic PIN), and it comes back until it is closed with the button.
 */
public final class VeloWhatsNew {

    static final String SEEN = "velo_whats_new_seen";
    private static boolean open = false;

    private VeloWhatsNew() {}

    public static boolean isOpen() {
        return open;
    }

    public static void maybeShow(final Activity activity, final XmppConnectionService service) {
        if (open || VeloIntro.isOpen() || activity.isFinishing() || service == null || VeloUpdater.account(service) == null) {
            return;
        }
        final SharedPreferences preferences = service.getPreferences();
        final int current = BuildConfig.VERSION_CODE;
        final int seen = preferences.getInt(SEEN, 0);
        if (seen >= current) {
            return;
        }
        final String text = activity.getString(R.string.velo_whats_new_text).trim();
        if (text.isEmpty() || (seen == 0 && !isUpdate(activity))) {
            preferences.edit().putInt(SEEN, current).apply();
            return;
        }
        open = true;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.velo_whats_new_title, BuildConfig.VERSION_NAME))
                .setMessage(text)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.velo_whats_new_ok,
                        (d, w) -> preferences.edit().putInt(SEEN, current).apply())
                .setOnDismissListener(d -> open = false)
                .show();
    }

    /** True when this package was installed over an older one, false on a first install. */
    private static boolean isUpdate(final Activity activity) {
        try {
            final PackageInfo info =
                    activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (final Exception e) {
            return false;
        }
    }
}
