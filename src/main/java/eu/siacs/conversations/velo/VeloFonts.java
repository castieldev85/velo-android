package eu.siacs.conversations.velo;

import android.content.Context;
import android.graphics.Typeface;
import android.preference.PreferenceManager;

import androidx.core.content.res.ResourcesCompat;

import eu.siacs.conversations.R;

/** Velo 0.28: the typeface chosen in Personalizza. Inter sits under the noto_sans_* resource names. */
public final class VeloFonts {

    public static final String MODERN = "modern";
    public static final String ROUNDED = "rounded";
    public static final String ELEGANT = "elegant";

    private VeloFonts() {}

    public static String current(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(VeloThemes.FONT, MODERN);
    }

    public static void applyTo(final android.app.Activity activity) {
        final String font = current(activity);
        if (ROUNDED.equals(font)) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Velo_FontRounded, true);
        } else if (ELEGANT.equals(font)) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Velo_FontElegant, true);
        }
    }

    public static Typeface get(final Context context, final int inter) {
        final String font = current(context);
        final boolean bold = inter == R.font.noto_sans_bold || inter == R.font.noto_sans_bold_italic;
        if (ROUNDED.equals(font)) {
            return ResourcesCompat.getFont(context, bold ? R.font.velo_nunito_bold : R.font.velo_nunito_regular);
        }
        if (ELEGANT.equals(font)) {
            return ResourcesCompat.getFont(context, bold ? R.font.velo_lora_bold : R.font.velo_lora_regular);
        }
        return ResourcesCompat.getFont(context, inter);
    }
}
