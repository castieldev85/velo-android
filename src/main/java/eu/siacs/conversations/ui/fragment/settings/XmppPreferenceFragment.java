package eu.siacs.conversations.ui.fragment.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import eu.siacs.conversations.velo.Log;
import android.view.View;

import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.Preference;

import com.rarepebble.colorpicker.ColorPreference;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.primitives.Ints;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.velo.VeloUi;

public abstract class XmppPreferenceFragment extends PreferenceFragmentCompat {

    private final SharedPreferences.OnSharedPreferenceChangeListener
            sharedPreferenceChangeListener =
                    (sharedPreferences, key) -> {
                        if (key == null) {
                            return;
                        }
                        if (isAdded()) {
                            onSharedPreferenceChanged(key);
                        }
                    };

    protected void onSharedPreferenceChanged(@NonNull String key) {
        Log.d(Config.LOGTAG, "onSharedPreferenceChanged(" + key + ")");
    }

    public void onBackendConnected() {}

    // Velo 0.16: icons on a coloured square, in the colour of this page's entry on the main settings
    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getPreferenceScreen() != null) {
            veloColourIcons(getPreferenceScreen(), veloIconColour());
        }
    }

    private int veloIconColour() {
        if (this instanceof InterfaceBubblesSettingsFragment) {
            return VeloUi.COLOUR_APPEARANCE;
        } else if (this instanceof PrivacySettingsFragment) {
            return VeloUi.COLOUR_PRIVACY;
        } else if (this instanceof SecuritySettingsFragment) {
            return VeloUi.COLOUR_SECURITY;
        } else if (this instanceof NotificationsSettingsFragment) {
            return VeloUi.COLOUR_NOTIFICATIONS;
        } else if (this instanceof AttachmentsSettingsFragment) {
            return VeloUi.COLOUR_MEDIA;
        } else if (this instanceof BackupSettingsFragment) {
            return VeloUi.COLOUR_BACKUP;
        }
        return VeloUi.COLOUR_OTHER;
    }

    private void veloColourIcons(final PreferenceGroup group, final int colour) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup subGroup) {
                veloColourIcons(subGroup, colour);
            }
            if (preference.getIcon() != null) {
                preference.setIcon(VeloUi.settingsIcon(requireContext(), preference.getIcon(), colour));
            }
        }
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof ColorPreference) {
            ((ColorPreference) preference).showDialog(this, 0);
        } else super.onDisplayPreferenceDialog(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        final var sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) {
            sharedPreferences.registerOnSharedPreferenceChangeListener(
                    this.sharedPreferenceChangeListener);
        }
        final var xmppActivity = requireXmppActivity();
        if (xmppActivity.xmppConnectionService != null) {
            this.onBackendConnected();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        final var sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    this.sharedPreferenceChangeListener);
        }
    }

    protected void reconnectAccounts() {
        final var service = requireService();
        for (final Account account : service.getAccounts()) {
            if (account.isEnabled()) {
                service.reconnectAccountInBackground(account);
            }
        }
    }

    protected XmppActivity requireXmppActivity() {
        final var activity = requireActivity();
        if (activity instanceof XmppActivity xmppActivity) {
            return xmppActivity;
        }
        throw new IllegalStateException();
    }

    protected XmppConnectionService requireService() {
        final var xmppActivity = requireXmppActivity();
        final var service = xmppActivity.xmppConnectionService;
        if (service != null) {
            return service;
        }
        throw new IllegalStateException();
    }

    protected void runOnUiThread(final Runnable runnable) {
        requireActivity().runOnUiThread(runnable);
    }

    protected static String timeframeValueToName(final Context context, final int value) {
        if (value == 0) {
            return context.getString(R.string.never);
        } else {
            return TimeFrameUtils.resolve(context, 1000L * value);
        }
    }

    protected void setValues(
            final ListPreference listPreference,
            @ArrayRes int resId,
            final Function<Integer, String> valueToName) {
        final int[] choices = getResources().getIntArray(resId);
        final CharSequence[] entries = new CharSequence[choices.length];
        final CharSequence[] entryValues = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; ++i) {
            final int value = choices[i];
            entryValues[i] = String.valueOf(choices[i]);
            entries[i] = valueToName.apply(value);
        }
        listPreference.setEntries(entries);
        listPreference.setEntryValues(entryValues);
        final CharSequence staticSummary = listPreference.getSummary();
        listPreference.setSummaryProvider(
                new Preference.SummaryProvider<ListPreference>() {
                    @Nullable
                    @Override
                    public CharSequence provideSummary(@NonNull ListPreference preference) {
                        final Integer value =
                                Ints.tryParse(Strings.nullToEmpty(preference.getValue()));
                        final CharSequence valueName = valueToName.apply(value == null ? 0 : value);
                        if (staticSummary != null && staticSummary.length() > 0) {
                            return staticSummary + "\n" + valueName;
                        }
                        return valueName;
                    }
                });
    }
}
