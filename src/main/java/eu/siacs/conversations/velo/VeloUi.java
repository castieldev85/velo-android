package eu.siacs.conversations.velo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

import androidx.annotation.Nullable;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;

/** Velo: small pieces of the new look (0.15) shared by several screens. */
public final class VeloUi {

    private VeloUi() {}

    /** "Encrypted with OMEMO2" / "Encrypted with OMEMO" for a chat's top bar, or null. */
    @Nullable
    public static String encryptionLabel(final Context context, final Conversation conversation) {
        return switch (conversation.getNextEncryption()) {
            case Message.ENCRYPTION_AXOLOTL_OMEMO2 -> context.getString(R.string.velo_encrypted_omemo2);
            case Message.ENCRYPTION_AXOLOTL -> context.getString(R.string.velo_encrypted_omemo);
            default -> null;
        };
    }

    // Velo 0.16: a settings icon in white on a coloured rounded square (main settings and every sub-page)
    /** Velo 0.28: the top bar: the theme's main colour by day, a dark surface by night. */
    public static int barColor(final Context context) {
        final android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.contains(VeloThemes.BAR)) {
            return prefs.getInt(VeloThemes.BAR, 0);
        }
        final boolean night = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return com.google.android.material.color.MaterialColors.getColor(context,
                night ? com.google.android.material.R.attr.colorSurfaceContainerHigh : androidx.appcompat.R.attr.colorPrimary, 0);
    }

    /** Velo 0.28: paints the bar of a screen, title and icons included. */
    public static void paintBar(final android.app.Activity activity) {
        final androidx.appcompat.widget.Toolbar toolbar = findToolbar(activity.getWindow().getDecorView());
        if (toolbar == null) {
            return;
        }
        final int bar = barColor(activity);
        final int text = VeloThemes.onColor(bar);
        toolbar.setBackgroundColor(bar);
        toolbar.setTitleTextColor(text);
        toolbar.setSubtitleTextColor(text);
        final android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(text);
        final Runnable icons = () -> {
            if (toolbar.getNavigationIcon() != null) {
                toolbar.getNavigationIcon().mutate().setTint(text);
            }
            if (toolbar.getOverflowIcon() != null) {
                toolbar.getOverflowIcon().mutate().setTint(text);
            }
            // Velo 0.39.3: tint only the items that do not have it yet. Setting it again on every
            // layout made each menu icon ask for a new layout, which called this again: an endless
            // loop of about 200 layouts per second, CPU and battery, and taps ignored at start-up.
            final android.view.Menu menu = toolbar.getMenu();
            for (int i = 0; i < menu.size(); i++) {
                final android.view.MenuItem item = menu.getItem(i);
                if (androidx.core.view.MenuItemCompat.getIconTintList(item) != tint) {
                    androidx.core.view.MenuItemCompat.setIconTintList(item, tint);
                }
            }
        };
        icons.run();
        toolbar.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> icons.run());
        final android.view.ViewParent parent = toolbar.getParent();
        if (parent instanceof com.google.android.material.appbar.AppBarLayout) {
            final android.view.View appBar = (android.view.View) parent;
            appBar.setBackgroundColor(bar);
            appBar.setElevation(0);
            if (appBar.getParent() instanceof android.view.View) {
                paintTabs((android.view.ViewGroup) appBar, bar, text);
            }
        }
        androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightStatusBars(text != 0xFFFFFFFF);
        paintStatusStrip(activity, bar);
    }

    private static final java.util.Map<android.app.Activity, android.graphics.drawable.ColorDrawable> STATUS_STRIPS =
            new java.util.WeakHashMap<>();

    /**
     * Velo 0.29: the strip behind the clock and the battery takes the bar's colour too, or their
     * icons, light on a coloured bar, vanish on a light page. Android 15 and later ignore
     * setStatusBarColor, so the strip is drawn over the window.
     */
    private static void paintStatusStrip(final android.app.Activity activity, final int bar) {
        activity.getWindow().setStatusBarColor(bar);
        final android.graphics.drawable.ColorDrawable known = STATUS_STRIPS.get(activity);
        if (known != null) {
            known.setColor(bar);
            return;
        }
        final android.graphics.drawable.ColorDrawable strip = new android.graphics.drawable.ColorDrawable(bar);
        STATUS_STRIPS.put(activity, strip);
        final android.view.View decor = activity.getWindow().getDecorView();
        final Runnable place = () -> {
            final androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor);
            final int top = insets == null ? 0
                    : insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            strip.setBounds(0, 0, decor.getWidth(), top);
        };
        decor.getOverlay().add(strip);
        place.run();
        decor.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> place.run());
    }

    private static void paintTabs(final android.view.ViewGroup group, final int bar, final int text) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof com.google.android.material.tabs.TabLayout tabs) {
                tabs.setBackgroundColor(bar);
                tabs.setTabTextColors(androidx.core.graphics.ColorUtils.setAlphaComponent(text, 0xB0), text);
                tabs.setSelectedTabIndicatorColor(text);
            }
        }
    }

    @Nullable
    private static androidx.appcompat.widget.Toolbar findToolbar(final android.view.View view) {
        if (view instanceof androidx.appcompat.widget.Toolbar toolbar) {
            return toolbar;
        }
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                final androidx.appcompat.widget.Toolbar found = findToolbar(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public static final int COLOUR_APPEARANCE = 0xFF7E57C2;
    public static final int COLOUR_PRIVACY = 0xFF1E88E5;
    public static final int COLOUR_SECURITY = 0xFF43A047;
    public static final int COLOUR_NOTIFICATIONS = 0xFFE53935;
    public static final int COLOUR_MEDIA = 0xFFFB8C00;
    public static final int COLOUR_BACKUP = 0xFF00897B;
    public static final int COLOUR_UPDATE = 0xFF3949AB;
    public static final int COLOUR_OTHER = 0xFF546E7A;

    @Nullable
    public static Drawable settingsIcon(final Context context, @Nullable final Drawable icon, final int colour) {
        if (icon == null || icon instanceof LayerDrawable) {
            return icon;
        }
        final float density = context.getResources().getDisplayMetrics().density;
        final GradientDrawable square = new GradientDrawable();
        square.setShape(GradientDrawable.RECTANGLE);
        square.setCornerRadius(12 * density);
        square.setColor(colour);
        square.setSize(Math.round(40 * density), Math.round(40 * density));
        final Drawable glyph = icon.mutate();
        glyph.setTint(Color.WHITE);
        final LayerDrawable tile = new LayerDrawable(new Drawable[] {square, glyph});
        final int inset = Math.round(9 * density);
        tile.setLayerInset(1, inset, inset, inset, inset);
        return tile;
    }
}
