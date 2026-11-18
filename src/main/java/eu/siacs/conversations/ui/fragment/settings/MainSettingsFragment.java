package eu.siacs.conversations.ui.fragment.settings;

import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.common.base.Strings;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.R;
import eu.siacs.conversations.velo.VeloUi;

public class MainSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey);
        final var about = findPreference("about");
        final var connection = findPreference("connection");
        final var up = findPreference("up");
        if (about == null || connection == null || up == null) {
            throw new IllegalStateException(
                    "The preference resource file is missing some preferences");
        }
        about.setTitle(getString(R.string.title_activity_about_x, BuildConfig.APP_NAME));
        // Velo: just the version, no phone model or library versions
        about.setSummary(BuildConfig.APP_NAME + " " + BuildConfig.VERSION_NAME);
        about.setCopyingEnabled(true);
        if (ConnectionSettingsFragment.hideChannelDiscovery()) {
            connection.setSummary(R.string.pref_connection_summary);
        }
        // Velo: UnifiedPush stays out of the simplified settings (preferences_main.xml)
        up.setVisible(false);
        veloColourIcons();
        // Velo 0.28: Aspetto is one page, see velo/VeloThemesActivity
        findPreference("velo_appearance").setOnPreferenceClickListener(p -> {
            startActivity(new android.content.Intent(
                    requireContext(), eu.siacs.conversations.velo.VeloThemesActivity.class));
            return true;
        });
        final var veloUpdate = findPreference("velo_update");
        if (veloUpdate != null) {
            // Velo pubblica: aggiornamenti dallo store
            veloUpdate.setVisible(eu.siacs.conversations.velo.VeloUpdater.enabled());
            veloUpdate.setOnPreferenceClickListener(p -> {
                veloCheckUpdate();
                return true;
            });
        }
        final var veloDiagnostics = findPreference("velo_diagnostics");
        if (veloDiagnostics != null) {
            veloDiagnostics.setOnPreferenceClickListener(p -> {
                startActivity(new android.content.Intent(
                        requireContext(), eu.siacs.conversations.velo.VeloDiagnosticsActivity.class));
                return true;
            });
        }
        findPreference("extensions").setOnPreferenceClickListener((p) -> {
            getFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new it.velo.chat.ExtensionSettingsFragment())
                .addToBackStack(null)
                .commit();
            return true;
        });
    }

    // Velo 0.16: each entry's icon in white on its own coloured rounded square, one colour per entry
    private static final int[] VELO_ICON_COLOURS = {
            VeloUi.COLOUR_APPEARANCE, VeloUi.COLOUR_PRIVACY, VeloUi.COLOUR_SECURITY, VeloUi.COLOUR_NOTIFICATIONS,
            VeloUi.COLOUR_MEDIA, VeloUi.COLOUR_BACKUP, VeloUi.COLOUR_UPDATE, VeloUi.COLOUR_OTHER
    };

    private void veloColourIcons() {
        int colour = 0;
        for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
            final Preference preference = getPreferenceScreen().getPreference(i);
            if (!preference.isVisible() || preference.getIcon() == null) {
                continue;
            }
            preference.setIcon(VeloUi.settingsIcon(requireContext(), preference.getIcon(),
                    VELO_ICON_COLOURS[colour++ % VELO_ICON_COLOURS.length]));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.title_activity_settings);
        // Velo 0.20: opened from the "update available" notification
        final var intent = requireActivity().getIntent();
        if (intent != null && intent.getBooleanExtra(eu.siacs.conversations.velo.VeloUpdater.EXTRA_CHECK, false)) {
            intent.removeExtra(eu.siacs.conversations.velo.VeloUpdater.EXTRA_CHECK);
            veloCheckUpdate();
        }
        veloAdminRetries = 0;
        veloCheckAdmin();
    }

    private int veloAdminRetries = 0;

    // Velo 0.32: "Gestione utenti" appears only if the server offers one of the accounts its admin
    // commands; the service and the connection may still be starting, so it asks again for a while
    private void veloCheckAdmin() {
        final var preference = findPreference("velo_admin");
        if (preference == null || preference.isVisible()) {
            return;
        }
        final var activity = requireActivity();
        final var service = activity instanceof eu.siacs.conversations.ui.XmppActivity xmpp
                ? xmpp.xmppConnectionService : null;
        final var accounts = new java.util.ArrayList<eu.siacs.conversations.entities.Account>();
        if (service != null) {
            for (final var account : service.getAccounts()) {
                if (account.isEnabled() && account.isOnlineAndConnected()) {
                    accounts.add(account);
                }
            }
        }
        if (accounts.isEmpty()) {
            if (veloAdminRetries++ < 20) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (isAdded()) veloCheckAdmin();
                }, 1500);
            }
            return;
        }
        for (final var account : accounts) {
            eu.siacs.conversations.velo.VeloAdmin.checkAdmin(service, account, admin -> {
                if (!admin) return;
                activity.runOnUiThread(() -> {
                    if (!isAdded() || preference.isVisible()) return;
                    preference.setIcon(VeloUi.settingsIcon(requireContext(), preference.getIcon(), VeloUi.COLOUR_SECURITY));
                    preference.setOnPreferenceClickListener(p -> {
                        startActivity(new android.content.Intent(
                                requireContext(), eu.siacs.conversations.velo.VeloAdminActivity.class));
                        return true;
                    });
                    preference.setVisible(true);
                });
            });
        }
    }

    private int veloUpdateRetries = 0;

    // Velo 0.20: check, download (with progress) and hand the APK to Android's installer
    private void veloCheckUpdate() {
        final var activity = requireActivity();
        final var service = activity instanceof eu.siacs.conversations.ui.XmppActivity xmpp
                ? xmpp.xmppConnectionService : null;
        final var account = eu.siacs.conversations.velo.VeloUpdater.account(service);
        if (account == null) {
            // Velo 0.24: arriving from the button or the notification, the service may still be starting
            if (veloUpdateRetries++ < 10) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (isAdded()) veloCheckUpdate();
                }, 1000);
                return;
            }
            veloUpdateRetries = 0;
            android.widget.Toast.makeText(activity, R.string.velo_update_not_ready, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        veloUpdateRetries = 0;
        android.widget.Toast.makeText(activity, R.string.velo_update_checking, android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final var info = eu.siacs.conversations.velo.VeloUpdater.fetch(account);
                activity.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (info == null || !info.isNewer()) {
                        android.widget.Toast.makeText(activity,
                                getString(R.string.velo_update_none, BuildConfig.VERSION_NAME),
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                            .setTitle(getString(R.string.velo_update_available_title))
                            .setMessage(getString(R.string.velo_update_question, info.versionName, BuildConfig.VERSION_NAME))
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.velo_update_do, (d, w) -> veloDownload(account, info))
                            .show();
                });
            } catch (final java.io.IOException e) {
                activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                        R.string.velo_update_failed, android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void veloDownload(final eu.siacs.conversations.entities.Account account,
            final eu.siacs.conversations.velo.VeloUpdater.Info info) {
        final var activity = requireActivity();
        final var bar = new com.google.android.material.progressindicator.LinearProgressIndicator(activity);
        bar.setMax(100);
        final int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        final var box = new android.widget.FrameLayout(activity);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(bar);
        final var dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(getString(R.string.velo_update_downloading, info.versionName))
                .setView(box)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            try {
                final var apk = eu.siacs.conversations.velo.VeloUpdater.download(activity, account, info,
                        percent -> activity.runOnUiThread(() -> bar.setProgressCompat(percent, true)));
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    eu.siacs.conversations.velo.VeloUpdater.install(activity, apk);
                });
            } catch (final java.io.IOException e) {
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    android.widget.Toast.makeText(activity, R.string.velo_update_failed,
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
