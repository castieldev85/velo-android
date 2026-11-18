package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;

/**
 * Velo: ready-made themes, Telegram style. Classico and Notte are the app's own light and dark
 * palettes (colors-themed.xml) and work on every Android version; every other preset, and any
 * accent colour, goes through the upstream project's custom colours (ThemeHelper, Android 11+), plus the
 * Velo keys below for the chat bubbles and the chat background.
 */
public final class VeloThemes {

    public static final String PRESET = "velo_theme_preset";
    public static final String ACCENT = "velo_theme_accent";
    public static final String AUTO_NIGHT = "velo_auto_night";
    /** Set by "Crea il tuo tema": only then Settings > Interface shows the colour pickers. */
    public static final String HAND_MADE = "velo_theme_hand_made";
    private static final String BUBBLE_OUT = "velo_bubble_out";
    private static final String BUBBLE_IN = "velo_bubble_in";
    private static final String CHAT_BG = "velo_chat_bg";
    private static final String DARK_BUBBLE_OUT = "velo_dark_bubble_out";
    private static final String DARK_BUBBLE_IN = "velo_dark_bubble_in";
    private static final String DARK_CHAT_BG = "velo_dark_chat_bg";
    // Velo 0.28: "Personalizza"
    public static final String BAR = "velo_bar_colour";
    public static final String CHAT_PATTERN = "velo_chat_pattern";
    public static final String FONT = "velo_font";
    public static final String SAVED = "velo_saved_theme";
    public static final String PATTERN_DRAWINGS = "drawings";
    public static final String PATTERN_DOTS = "dots";
    public static final String PATTERN_WAVES = "waves";
    public static final String PATTERN_PLAIN = "plain";
    public static final String PATTERN_GRADIENT = "gradient";

    public static final class Preset {
        public final String key;
        public final int nameRes;
        public final boolean dark;
        public final int primary;
        public final int background;
        public final int bubbleOut;
        public final int bubbleIn;
        public final int chatBg;
        /** Whether a chosen accent colour also paints the sent bubbles. */
        final boolean accentPaintsBubble;

        Preset(final String key, final int nameRes, final boolean dark, final int primary,
                final int background, final int bubbleOut, final int bubbleIn, final int chatBg,
                final boolean accentPaintsBubble) {
            this.key = key;
            this.nameRes = nameRes;
            this.dark = dark;
            this.primary = primary;
            this.background = background;
            this.bubbleOut = bubbleOut;
            this.bubbleIn = bubbleIn;
            this.chatBg = chatBg;
            this.accentPaintsBubble = accentPaintsBubble;
        }

        public int bubbleOut(@Nullable final Integer accent) {
            return accent != null && accentPaintsBubble ? accent : bubbleOut;
        }
    }

    public static final Preset CLASSICO = new Preset("classico", R.string.velo_theme_classico,
            false, 0xFF1F6FD6, 0xFFFFFFFF, 0xFFD9F5C7, 0xFFFFFFFF, 0xFFBFD8EE, false);
    public static final Preset GIORNO = new Preset("giorno", R.string.velo_theme_giorno,
            false, 0xFF1F6FD6, 0xFFFFFFFF, 0xFF1F6FD6, 0xFFFFFFFF, 0xFFDDE4EE, true);
    public static final Preset NOTTE = new Preset("notte", R.string.velo_theme_notte,
            true, 0xFF7C8CFF, 0xFF141936, 0xFF3A4596, 0xFF1F2650, 0xFF0D1126, true);
    public static final Preset NERO = new Preset("nero", R.string.velo_theme_nero,
            true, 0xFF4CC38A, 0xFF000000, 0xFF2E6E4E, 0xFF1C1C1E, 0xFF000000, false);
    public static final Preset TRAMONTO = new Preset("tramonto", R.string.velo_theme_tramonto,
            false, 0xFFB84A33, 0xFFFFF8F3, 0xFFFDE3CF, 0xFFFFFFFF, 0xFFE6C0A6, false);

    // Velo 0.32: more themes
    public static final Preset OCEANO = new Preset("oceano", R.string.velo_theme_oceano,
            false, 0xFF0B7A8A, 0xFFF4FAFB, 0xFFD4F3F5, 0xFFFFFFFF, 0xFFB9DCE1, false);
    public static final Preset LAVANDA = new Preset("lavanda", R.string.velo_theme_lavanda,
            false, 0xFF6A4FC8, 0xFFFBF9FF, 0xFFEAE3FF, 0xFFFFFFFF, 0xFFD6CCF0, false);
    public static final Preset CILIEGIO = new Preset("ciliegio", R.string.velo_theme_ciliegio,
            false, 0xFFC0306A, 0xFFFFF8FA, 0xFFFDDDE9, 0xFFFFFFFF, 0xFFEBC3D3, false);
    public static final Preset FORESTA = new Preset("foresta", R.string.velo_theme_foresta,
            true, 0xFF7DCB8A, 0xFF0F1A14, 0xFF2E5E3E, 0xFF1C2B22, 0xFF0A130E, false);
    public static final Preset AMBRA = new Preset("ambra", R.string.velo_theme_ambra,
            true, 0xFFF2B544, 0xFF16120B, 0xFF5C4416, 0xFF2A231A, 0xFF0F0C07, false);
    public static final Preset AURORA = new Preset("aurora", R.string.velo_theme_aurora,
            true, 0xFF5EE0C8, 0xFF0B1120, 0xFF1D4A5A, 0xFF1A2236, 0xFF070B16, false);

    public static final List<Preset> PRESETS = Arrays.asList(CLASSICO, GIORNO, NOTTE, NERO, TRAMONTO,
            OCEANO, LAVANDA, CILIEGIO, FORESTA, AMBRA, AURORA);

    private VeloThemes() {}

    // Velo 0.19: chat settings as in Telegram (text size, message corners, chat list rows).
    // Stored in the default preferences.
    public static final String TEXT_SIZE = "velo_text_size";
    public static final String CORNERS = "velo_bubble_corners";
    public static final String LIST_LINES = "velo_list_lines";
    /** The corner size the bubble drawables are drawn with (velo_dimens, bubble_radius_large). */
    public static final int DEFAULT_CORNERS = 20;

    public static int textSize(final Context context) {
        final SharedPreferences prefs = prefs(context);
        if (prefs.contains(TEXT_SIZE)) {
            return prefs.getInt(TEXT_SIZE, 14);
        }
        return prefs.getBoolean(AppSettings.LARGE_FONT, false) ? 18 : 14;
    }

    public static int corners(final Context context) {
        return prefs(context).getInt(CORNERS, DEFAULT_CORNERS);
    }

    public static int listLines(final Context context) {
        return prefs(context).getInt(LIST_LINES, 2);
    }

    public static Preset byKey(@Nullable final String key, final Preset fallback) {
        for (final Preset preset : PRESETS) {
            if (preset.key.equals(key)) {
                return preset;
            }
        }
        return fallback;
    }

    /** Presets other than Classico and Notte, and accents, need Android 11 (custom colours). */
    public static boolean customColorsAvailable() {
        return Build.VERSION.SDK_INT >= 30;
    }

    public static Preset current(final Context context) {
        final String key = prefs(context).getString(PRESET, CLASSICO.key);
        for (final Preset preset : PRESETS) {
            if (preset.key.equals(key)) {
                return preset;
            }
        }
        return CLASSICO;
    }

    @Nullable
    public static Integer currentAccent(final Context context) {
        final SharedPreferences prefs = prefs(context);
        return prefs.contains(ACCENT) ? prefs.getInt(ACCENT, 0) : null;
    }

    public static boolean autoNight(final Context context) {
        return prefs(context).getBoolean(AUTO_NIGHT, false);
    }

    public static void apply(final Context context, final Preset preset,
            @Nullable final Integer accent, final boolean autoNight) {
        final SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(PRESET, preset.key).putBoolean(AUTO_NIGHT, autoNight).putBoolean(HAND_MADE, false).remove(BAR);
        if (accent == null) {
            editor.remove(ACCENT);
        } else {
            editor.putInt(ACCENT, accent);
        }
        final boolean builtIn = accent == null && (preset == CLASSICO || preset == NOTTE);
        if (builtIn || !customColorsAvailable()) {
            editor.putString(AppSettings.THEME,
                    autoNight ? "automatic" : (preset.dark ? "dark" : "light"));
            for (final String key : new String[] {BUBBLE_OUT, BUBBLE_IN, CHAT_BG,
                    DARK_BUBBLE_OUT, DARK_BUBBLE_IN, DARK_CHAT_BG}) {
                editor.remove(key);
            }
        } else {
            // With automatic night mode a light preset pairs with Notte, a dark one with Classico.
            final Preset light = preset.dark ? CLASSICO : preset;
            final Preset dark = preset.dark ? preset : NOTTE;
            final Integer lightAccent = light == preset ? accent : null;
            final Integer darkAccent = dark == preset ? accent : null;
            editor.putString(AppSettings.THEME, "custom")
                    .putBoolean("custom_theme_automatic", autoNight)
                    .putBoolean("custom_theme_dark", preset.dark)
                    .putBoolean("custom_theme_color_match", true);
            final int lightPrimary = lightAccent != null ? lightAccent : light.primary;
            editor.putInt("custom_theme_primary", lightPrimary)
                    .putInt("custom_theme_primary_dark", lightPrimary)
                    .putInt("custom_theme_accent", lightPrimary)
                    .putInt("custom_theme_background_primary", light.background)
                    .putInt(BUBBLE_OUT, light.bubbleOut(lightAccent))
                    .putInt(BUBBLE_IN, light.bubbleIn)
                    .putInt(CHAT_BG, light.chatBg);
            final int darkPrimary = darkAccent != null ? darkAccent : dark.primary;
            editor.putInt("custom_dark_theme_primary", darkPrimary)
                    .putInt("custom_dark_theme_primary_dark", darkPrimary)
                    .putInt("custom_dark_theme_accent", darkPrimary)
                    .putInt("custom_dark_theme_background_primary", dark.background)
                    .putInt(DARK_BUBBLE_OUT, dark.bubbleOut(darkAccent))
                    .putInt(DARK_BUBBLE_IN, dark.bubbleIn)
                    .putInt(DARK_CHAT_BG, dark.chatBg);
        }
        editor.commit();
        AppCompatDelegate.setDefaultNightMode(Conversations.getDesiredNightMode(context));
    }

    /**
     * Called by ThemeHelper.applyCustomColors: the chat bubbles get their own colours, sent =
     * primary container, received = secondary container (see MessageAdapter.BubbleColor).
     */
    public static void addBubbleColors(final SharedPreferences prefs, final HashMap<Integer, Integer> colors) {
        if (prefs.contains(BUBBLE_OUT)) {
            final int out = prefs.getInt(BUBBLE_OUT, 0);
            final int in = prefs.getInt(BUBBLE_IN, 0);
            colors.put(R.color.md_theme_light_primaryContainer, out);
            colors.put(R.color.md_theme_light_onPrimaryContainer, onColor(out));
            colors.put(R.color.md_theme_light_secondaryContainer, in);
            colors.put(R.color.md_theme_light_onSecondaryContainer, onColor(in));
        }
        if (prefs.contains(DARK_BUBBLE_OUT)) {
            final int out = prefs.getInt(DARK_BUBBLE_OUT, 0);
            final int in = prefs.getInt(DARK_BUBBLE_IN, 0);
            colors.put(R.color.md_theme_dark_primaryContainer, out);
            colors.put(R.color.md_theme_dark_onPrimaryContainer, onColor(out));
            colors.put(R.color.md_theme_dark_secondaryContainer, in);
            colors.put(R.color.md_theme_dark_onSecondaryContainer, onColor(in));
        }
    }

    /** Plain colour behind the chat, when the user has not picked an image. */
    public static int chatBackground(final Context context, final boolean night) {
        final SharedPreferences prefs = prefs(context);
        final String key = night ? DARK_CHAT_BG : CHAT_BG;
        if (Conversations.isCustomColorsDesired(context) && prefs.contains(key)) {
            return prefs.getInt(key, 0);
        }
        return night ? NOTTE.chatBg : CLASSICO.chatBg;
    }

    /** Velo 0.28: "Personalizza" writes every colour by hand. Night or day follows the page colour. */
    public static void applyColours(final Context context, final int bar, final int buttons,
            final int sent, final int received, final int wall, final int page) {
        final boolean dark = onColor(page) == 0xFFFFFFFF;
        prefs(context).edit()
                .putBoolean(HAND_MADE, true).putInt(BAR, bar)
                .putString(AppSettings.THEME, "custom")
                .putBoolean("custom_theme_automatic", false)
                .putBoolean("custom_theme_dark", dark)
                .putBoolean("custom_theme_color_match", true)
                .putInt("custom_theme_primary", buttons).putInt("custom_theme_primary_dark", buttons)
                .putInt("custom_theme_accent", buttons).putInt("custom_theme_background_primary", page)
                .putInt(BUBBLE_OUT, sent).putInt(BUBBLE_IN, received).putInt(CHAT_BG, wall)
                .putInt("custom_dark_theme_primary", buttons).putInt("custom_dark_theme_primary_dark", buttons)
                .putInt("custom_dark_theme_accent", buttons).putInt("custom_dark_theme_background_primary", page)
                .putInt(DARK_BUBBLE_OUT, sent).putInt(DARK_BUBBLE_IN, received).putInt(DARK_CHAT_BG, wall)
                .commit();
        AppCompatDelegate.setDefaultNightMode(Conversations.getDesiredNightMode(context));
    }

    public static int savedColour(final Context context, final boolean night, final int part, final int fallback) {
        final String[] day = {BUBBLE_OUT, BUBBLE_IN, CHAT_BG};
        final String[] dark = {DARK_BUBBLE_OUT, DARK_BUBBLE_IN, DARK_CHAT_BG};
        return prefs(context).getInt(night ? dark[part] : day[part], fallback);
    }

    /** A drawn chat background: SVG path data on an 86dp tile repeated over the wall colour. */
    public static final class Wallpaper {
        public final String key;
        public final int nameRes;
        final String path;
        final float width;

        Wallpaper(final String key, final int nameRes, final String path, final float width) {
            this.key = key;
            this.nameRes = nameRes;
            this.path = path;
            this.width = width;
        }
    }

    // Velo 0.32.1: more backgrounds; the ones touching the tile edges (rhombs, notebook, zigzag,
    // waves) continue into the next tile, the others stay inside it
    public static final List<Wallpaper> WALLPAPERS = Arrays.asList(
            new Wallpaper(PATTERN_DRAWINGS, R.string.velo_customize_wall_drawings,
                    "M10 14h16a5 5 0 0 1 5 5v8a5 5 0 0 1-5 5H17l-6 5 1-5a5 5 0 0 1-5-5v-8a5 5 0 0 1 3-5ZM54 22a8 8 0 1 0 16 0 8 8 0 1 0-16 0M56 62l8-12 8 12M60 58h8M20 58l3 7 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1Z", 2f),
            new Wallpaper(PATTERN_DOTS, R.string.velo_customize_wall_dots,
                    "M20 20h.01M63 20h.01M41 63h.01M84 63h.01M0 63h.01", 5f),
            new Wallpaper(PATTERN_WAVES, R.string.velo_customize_wall_waves,
                    "M0 20c14-12 29-12 43 0s29 12 43 0M0 63c14-12 29-12 43 0s29 12 43 0", 2.5f),
            new Wallpaper("stars", R.string.velo_customize_wall_stars,
                    "M18 8v14M11 15h14M64 54v10M59 59h10M66 22a9 9 0 1 0 9 12a7 7 0 1 1-9-12ZM44 20h.01M8 44h.01M30 40h.01M12 66h.01M36 76h.01M78 80h.01M50 36h.01", 2.2f),
            new Wallpaper("hearts", R.string.velo_customize_wall_hearts,
                    "M22 26c-4-6-14-4-12 4 2 6 12 12 12 12s10-6 12-12c2-8-8-10-12-4ZM62 60c-3-4.5-10.5-3-9 3 1.5 4.5 9 9 9 9s7.5-4.5 9-9c1.5-6-6-7.5-9-3Z", 2f),
            new Wallpaper("leaves", R.string.velo_customize_wall_leaves,
                    "M12 40c0-14 12-26 28-26 0 16-12 26-28 26ZM12 40l20-18M48 78c4-13 17-20 30-16-4 13-17 20-30 16ZM48 78l22-11", 2f),
            new Wallpaper("space", R.string.velo_customize_wall_space,
                    "M21 30a9 9 0 1 0 18 0a9 9 0 1 0-18 0M14 36c8 4 26 0 32-9M64 58l2 5 5 2-5 2-2 5-2-5-5-2 5-2ZM66 16h.01M78 38h.01M10 70h.01M40 76h.01", 2f),
            new Wallpaper("music", R.string.velo_customize_wall_music,
                    "M20 40V18l14-4v22M20 40a4 3 0 1 1-8 0a4 3 0 1 1 8 0M34 36a4 3 0 1 1-8 0a4 3 0 1 1 8 0M66 72V52l9 5M66 72a4 3 0 1 1-8 0a4 3 0 1 1 8 0", 2f),
            new Wallpaper("bubbles", R.string.velo_customize_wall_bubbles,
                    "M10 24a10 10 0 1 0 20 0a10 10 0 1 0-20 0M57 18a5 5 0 1 0 10 0a5 5 0 1 0-10 0M44 60a14 14 0 1 0 28 0a14 14 0 1 0-28 0M12 70a4 4 0 1 0 8 0a4 4 0 1 0-8 0", 1.8f),
            new Wallpaper("rhombs", R.string.velo_customize_wall_rhombs,
                    "M0 43L43 0L86 43L43 86Z", 1.6f),
            new Wallpaper("zigzag", R.string.velo_customize_wall_zigzag,
                    "M0 22l10.75-8 10.75 8 10.75-8 10.75 8 10.75-8 10.75 8 10.75-8 10.75 8M0 65l10.75-8 10.75 8 10.75-8 10.75 8 10.75-8 10.75 8 10.75-8 10.75 8", 2.2f),
            new Wallpaper("notebook", R.string.velo_customize_wall_notebook,
                    "M0 .5H86M0 22H86M0 43.5H86M0 65H86M.5 0V86M22 0V86M43.5 0V86M65 0V86", 1f));

    public static final String PATTERN_GLOW = "glow";

    @Nullable
    static Wallpaper wallpaper(@Nullable final String key) {
        for (final Wallpaper wallpaper : WALLPAPERS) {
            if (wallpaper.key.equals(key)) {
                return wallpaper;
            }
        }
        return null;
    }

    /** The chat wallpaper: the theme's plain colour, with the chosen drawing repeated over it. */
    public static android.graphics.drawable.Drawable chatBackgroundDrawable(final Context context, final boolean night) {
        final int colour = chatBackground(context, night);
        return chatBackgroundDrawable(context, colour,
                prefs(context).getString(CHAT_PATTERN, PATTERN_DRAWINGS),
                com.google.android.material.color.MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, colour));
    }

    public static android.graphics.drawable.Drawable chatBackgroundDrawable(final Context context, final int colour,
            final String pattern, final int accent) {
        if (PATTERN_GRADIENT.equals(pattern)) {
            return new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {colour, androidx.core.graphics.ColorUtils.blendARGB(colour, accent, 0.5f)});
        }
        if (PATTERN_GLOW.equals(pattern)) {
            final android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {androidx.core.graphics.ColorUtils.blendARGB(colour, accent, 0.35f), colour});
            glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
            glow.setGradientCenter(0.5f, 0.35f);
            glow.setGradientRadius(context.getResources().getDisplayMetrics().widthPixels * 0.9f);
            return glow;
        }
        final Wallpaper wallpaper = wallpaper(pattern);
        if (wallpaper == null) {
            return new android.graphics.drawable.ColorDrawable(colour);
        }
        return tiledDrawing(context, wallpaper, colour,
                androidx.core.graphics.ColorUtils.blendARGB(colour, onColor(colour), 0.12f));
    }

    /** Velo 0.35: the welcome page: dark blue fading into the icon's lilac, the drawings faint over it. */
    public static android.graphics.drawable.Drawable welcomeBackground(final Context context) {
        final android.graphics.drawable.GradientDrawable sky = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF141936, 0xFF262D6B, 0xFF6A6FD8});
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                sky, tiledDrawing(context, wallpaper(PATTERN_DRAWINGS), 0, 0x1AFFFFFF)});
    }

    private static android.graphics.drawable.Drawable tiledDrawing(final Context context,
            final Wallpaper wallpaper, final int colour, final int line) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int size = Math.round(86 * density);
        final android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(tile);
        canvas.drawColor(colour);
        canvas.scale(density, density);
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        paint.setStrokeWidth(wallpaper.width);
        paint.setColor(line);
        canvas.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(wallpaper.path), paint);
        final android.graphics.drawable.BitmapDrawable drawable =
                new android.graphics.drawable.BitmapDrawable(context.getResources(), tile);
        drawable.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        return drawable;
    }

    static final int DARK_TEXT = 0xFF1B1B1F;
    static final int LIGHT_TEXT = 0xFFFFFFFF;
    /** WCAG: normal text needs 4.5, large text, icons and coloured words on the page 3. */
    static final double TEXT_CONTRAST = 4.5;
    static final double ACCENT_CONTRAST = 3.0;

    /** Black or white text, whichever reads better on {@code color}. */
    public static int onColor(final int color) {
        // Velo 0.32: by measured contrast; the old brightness threshold put white on light greens
        return contrast(DARK_TEXT, color) >= contrast(LIGHT_TEXT, color) ? DARK_TEXT : LIGHT_TEXT;
    }

    /**
     * The chip behind the date in a chat ("Today"), with white text: a deep shade of the chat
     * background on light walls, a lighter one on dark walls, always readable.
     */
    public static int dateChip(final int wall) {
        int chip = onColor(wall) == LIGHT_TEXT ? blend(wall, 0xFFFFFFFF, 0.16f) : blend(wall, 0xFF000000, 0.45f);
        for (int step = 0; step < 20 && contrast(LIGHT_TEXT, chip) < TEXT_CONTRAST; step++) {
            chip = blend(chip, 0xFF000000, 0.1f);
        }
        return chip;
    }

    /** WCAG relative luminance, from the RGB bits (android.graphics.Color is not there in tests). */
    static double luminance(final int color) {
        return 0.2126 * channel((color >> 16) & 0xFF)
                + 0.7152 * channel((color >> 8) & 0xFF)
                + 0.0722 * channel(color & 0xFF);
    }

    private static double channel(final int value) {
        final double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio, from 1 (same colour) to 21 (black on white). */
    public static double contrast(final int a, final int b) {
        final double la = luminance(a);
        final double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * {@code color}, or the nearest shade of it towards black or white that reads on {@code
     * background} with at least {@code min} contrast.
     */
    public static int readableOn(final int color, final int background, final double min) {
        if (contrast(color, background) >= min) {
            return color;
        }
        final int target = onColor(background);
        for (int step = 1; step <= 20; step++) {
            final int shade = blend(color, target, step / 20f);
            if (contrast(shade, background) >= min) {
                return shade;
            }
        }
        return target;
    }

    static int blend(final int from, final int to, final float amount) {
        final int r = Math.round(((from >> 16) & 0xFF) * (1 - amount) + ((to >> 16) & 0xFF) * amount);
        final int g = Math.round(((from >> 8) & 0xFF) * (1 - amount) + ((to >> 8) & 0xFF) * amount);
        final int b = Math.round((from & 0xFF) * (1 - amount) + (to & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Velo 0.32: called by ThemeHelper.applyCustomColors after it has put the custom colours. The
     * main colours are also words on the page (links, tabs, text buttons), so they must read on the
     * background; the text on them (buttons, switches, the + button) is black or white by contrast,
     * not Material's guess, which put white on light colours and dark on dark ones.
     */
    public static void fixContrast(final java.util.Map<Integer, Integer> colors) {
        fixContrast(colors, R.color.md_theme_light_background, new int[][] {
                {R.color.md_theme_light_primary, R.color.md_theme_light_onPrimary,
                        R.color.md_theme_light_primaryContainer, R.color.md_theme_light_onPrimaryContainer},
                {R.color.md_theme_light_secondary, R.color.md_theme_light_onSecondary,
                        R.color.md_theme_light_secondaryContainer, R.color.md_theme_light_onSecondaryContainer},
                {R.color.md_theme_light_tertiary, R.color.md_theme_light_onTertiary,
                        R.color.md_theme_light_tertiaryContainer, R.color.md_theme_light_onTertiaryContainer}});
        fixContrast(colors, R.color.md_theme_dark_background, new int[][] {
                {R.color.md_theme_dark_primary, R.color.md_theme_dark_onPrimary,
                        R.color.md_theme_dark_primaryContainer, R.color.md_theme_dark_onPrimaryContainer},
                {R.color.md_theme_dark_secondary, R.color.md_theme_dark_onSecondary,
                        R.color.md_theme_dark_secondaryContainer, R.color.md_theme_dark_onSecondaryContainer},
                {R.color.md_theme_dark_tertiary, R.color.md_theme_dark_onTertiary,
                        R.color.md_theme_dark_tertiaryContainer, R.color.md_theme_dark_onTertiaryContainer}});
    }

    private static void fixContrast(final java.util.Map<Integer, Integer> colors, final int backgroundRole,
            final int[][] roles) {
        final Integer background = colors.get(backgroundRole);
        if (background == null) {
            return;
        }
        for (final int[] role : roles) {
            final Integer main = colors.get(role[0]);
            if (main != null) {
                final int fixed = readableOn(main, background, ACCENT_CONTRAST);
                colors.put(role[0], fixed);
                colors.put(role[1], onColor(fixed));
            }
            final Integer container = colors.get(role[2]);
            if (container != null) {
                colors.put(role[3], onColor(container));
            }
        }
    }

    private static SharedPreferences prefs(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
