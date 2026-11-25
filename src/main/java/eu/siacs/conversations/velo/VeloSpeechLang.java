package eu.siacs.conversations.velo;

import android.content.Context;

import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.util.Locale;

/**
 * Velo 0.52: the language of dictation and voice-message transcription. Vosk does not detect the
 * language by itself: it is the one chosen in Settings → Data and media, or, with "Same as the
 * app", the app's language; English when the app speaks a language Velo has no model for.
 */
public final class VeloSpeechLang {

    public static final String PREF = "velo_speech_language";
    public static final String AUTO = "auto";

    /** The languages with a model, in the order shown to the user. */
    public static final String[] LANGS = {"it", "en", "es", "fr"};
    /** Their names, each in its own language (the same in every translation of Velo). */
    private static final String[] NAMES = {"Italiano", "English", "Español", "Français"};
    /** Download size of each model in MB (ZIP, rounded up). */
    private static final int[] SIZE_MB = {48, 40, 38, 41};

    private VeloSpeechLang() {}

    /** The language to use now. */
    public static String get(final Context context) {
        final String chosen =
                PreferenceManager.getDefaultSharedPreferences(context).getString(PREF, AUTO);
        return resolve(chosen, appLanguage(context));
    }

    /** Pure rule, for the tests: the chosen language, else the app's, else English. */
    static String resolve(final String chosen, final String appLanguage) {
        if (indexOf(chosen) >= 0) {
            return chosen;
        }
        if (indexOf(appLanguage) >= 0) {
            return appLanguage;
        }
        return "en";
    }

    public static String name(final String lang) {
        final int i = indexOf(lang);
        return i < 0 ? lang : NAMES[i];
    }

    public static int sizeMb(final String lang) {
        final int i = indexOf(lang);
        return i < 0 ? 0 : SIZE_MB[i];
    }

    /** The names of {@link #LANGS}, for a list to choose from. */
    public static String[] names() {
        return NAMES.clone();
    }

    static int indexOf(final String lang) {
        if (lang == null) {
            return -1;
        }
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(lang)) {
                return i;
            }
        }
        return -1;
    }

    private static String appLanguage(final Context context) {
        final LocaleListCompat locales =
                ConfigurationCompat.getLocales(context.getResources().getConfiguration());
        final Locale locale = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        return locale == null ? "" : locale.getLanguage();
    }
}
