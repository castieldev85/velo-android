package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.rarepebble.colorpicker.ColorPickerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.BaseActivity;
import eu.siacs.conversations.utils.ChatBackgroundHelper;
import eu.siacs.conversations.utils.ThemeHelper;

/**
 * Velo 0.28: "Personalizza". A small chat on top: touch a part of it, then pick its colour. Text
 * colours follow by themselves (VeloThemes.onColor), so nothing can become unreadable.
 */
public class VeloCustomizeActivity extends BaseActivity {

    private static final int[] SWATCHES = {
        0xFFFFFFFF, 0xFFF1F4F9, 0xFFCBD5E1, 0xFF64748B, 0xFF334155, 0xFF16233A, 0xFF0B1220, 0xFF000000,
        0xFFFDE2E4, 0xFFF9A8B8, 0xFFEF4444, 0xFFB91C1C, 0xFFFFE3C2, 0xFFFDBA74, 0xFFEA7A1F, 0xFFB4530E,
        0xFFFFF3B0, 0xFFFACC15, 0xFFCA8A04, 0xFF7A5B05, 0xFFD9F5C7, 0xFF86EFAC, 0xFF16A34A, 0xFF14532D,
        0xFFCCFBF1, 0xFF2DD4BF, 0xFF0E8078, 0xFF134E4A, 0xFFBFD8EE, 0xFF60A5FA, 0xFF1F6FD6, 0xFF1E3A8A,
        0xFFE0D7FF, 0xFFA78BFA, 0xFF7E48CC, 0xFF4C1D95, 0xFFFBCFE8, 0xFFF472B6, 0xFFBF3B78, 0xFF831843
    };
    private static final int SENT = 0, RECEIVED = 1, BAR = 2, BUTTONS = 3, WALL = 4, PAGE = 5;
    private static final int[] PART_NAMES = {
        R.string.velo_customize_part_sent, R.string.velo_customize_part_received, R.string.velo_customize_part_bar,
        R.string.velo_customize_part_buttons, R.string.velo_customize_part_wall, R.string.velo_customize_part_page
    };
    private static final int TAB_COLOURS = 0, TAB_WALL = 1, TAB_SHAPES = 2, TAB_TEXT = 3, TAB_BAR = 4;

    private final int[] colours = new int[6];
    private int part = SENT;
    private int tab = TAB_COLOURS;
    private int shownTab = -1;
    private ScrollView scroll;
    private LinearLayout root;
    private LinearLayout body;
    private SharedPreferences prefs;
    private int on;
    private int surface;
    private int surfaceHigh;
    private int text;
    private boolean photoPicked = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.applyCustomColors(this);
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        loadCurrent();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        setContentView(root);
        Activities.setStatusAndNavigationBarColors(this, root);
        render();
    }

    private void loadCurrent() {
        final boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        final View probe = new View(this);
        colours[BUTTONS] = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF1F6FD6);
        colours[BAR] = VeloUi.barColor(this);
        colours[PAGE] = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        colours[SENT] = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFD9F5C7);
        colours[RECEIVED] = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0xFFFFFFFF);
        colours[WALL] = VeloThemes.chatBackground(this, night);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(final int colour, final int radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colour);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void render() {
        // This screen follows the choices at once; the rest of the app on the way back
        on = VeloThemes.onColor(colours[PAGE]);
        surface = colours[PAGE];
        surfaceHigh = androidx.core.graphics.ColorUtils.blendARGB(colours[PAGE], on, 0.06f);
        text = on;
        // Velo 0.32.1: choosing in a long list (backgrounds) keeps the list where it was
        final int keepScroll = scroll != null && tab == shownTab ? scroll.getScrollY() : 0;
        shownTab = tab;
        root.removeAllViews();
        root.setBackgroundColor(surface);
        root.addView(buildBar());
        root.addView(buildPreview());
        root.addView(buildTabs());
        final FrameLayout rest = new FrameLayout(this);
        scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(84));
        scroll.addView(body);
        rest.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        final TextView save = new TextView(this);
        save.setText(R.string.velo_customize_save);
        save.setGravity(Gravity.CENTER);
        save.setTextSize(16);
        save.setTextColor(VeloThemes.onColor(colours[BUTTONS]));
        save.setBackground(rounded(colours[BUTTONS], dp(18)));
        save.setOnClickListener(v -> saveAsMine());
        final FrameLayout.LayoutParams saveParams = new FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM);
        saveParams.setMargins(dp(14), 0, dp(14), dp(14));
        rest.addView(save, saveParams);
        root.addView(rest, new LinearLayout.LayoutParams(-1, 0, 1f));
        switch (tab) {
            case TAB_WALL -> fillWall();
            case TAB_SHAPES -> fillShapes();
            case TAB_TEXT -> fillText();
            case TAB_BAR -> fillBar();
            default -> fillColours();
        }
        if (keepScroll > 0) {
            scroll.post(() -> scroll.scrollTo(0, keepScroll));
        }
    }

    private View buildBar() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(colours[BAR]);
        final int barText = VeloThemes.onColor(colours[BAR]);
        bar.setPadding(dp(4), dp(4), dp(10), dp(4));
        final android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(R.drawable.ic_arrow_back_24dp);
        back.setColorFilter(barText);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setContentDescription(getString(R.string.velo_customize_back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        final TextView title = new TextView(this);
        title.setText(R.string.velo_customize_title);
        title.setTextSize(19);
        title.setTextColor(barText);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView reset = new TextView(this);
        reset.setText(R.string.velo_customize_reset);
        reset.setTextSize(13);
        reset.setTextColor(barText);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(12), 0, dp(12), 0);
        reset.setBackground(rounded(0x33FFFFFF, dp(99)));
        reset.setOnClickListener(v -> reset());
        bar.addView(reset, new LinearLayout.LayoutParams(-2, dp(36)));
        return bar;
    }

    private View buildPreview() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(4));
        if (tab == TAB_BAR) {
            box.addView(barPreview(), new LinearLayout.LayoutParams(-1, dp(118)));
            final TextView hint = new TextView(this);
            hint.setText(R.string.velo_bar_hint);
            hint.setTextSize(13);
            hint.setGravity(Gravity.CENTER);
            hint.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(on, surface, 0.32f));
            hint.setPadding(dp(4), dp(8), dp(4), dp(4));
            box.addView(hint);
            return box;
        }
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipToOutline(true);
        card.setBackground(rounded(colours[WALL], dp(20)));
        final TextView head = new TextView(this);
        head.setText(R.string.velo_customize_sample_name);
        head.setTextColor(VeloThemes.onColor(colours[BAR]));
        head.setTextSize(15);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), 0, dp(14), 0);
        head.setBackgroundColor(colours[BAR]);
        head.setContentDescription(getString(R.string.velo_customize_part_bar));
        head.setOnClickListener(v -> pick(BAR));
        card.addView(head, new LinearLayout.LayoutParams(-1, dp(48)));
        final LinearLayout wall = new LinearLayout(this);
        wall.setOrientation(LinearLayout.VERTICAL);
        wall.setPadding(dp(12), dp(12), dp(12), dp(12));
        wall.setBackground(VeloThemes.chatBackgroundDrawable(this, colours[WALL],
                prefs.getString(VeloThemes.CHAT_PATTERN, VeloThemes.PATTERN_DRAWINGS), colours[BUTTONS]));
        wall.setContentDescription(getString(R.string.velo_customize_part_wall));
        wall.setOnClickListener(v -> pick(WALL));
        final int size = VeloThemes.textSize(this);
        wall.addView(bubble(R.string.velo_customize_sample_in, colours[RECEIVED], Gravity.START, size, RECEIVED));
        wall.addView(bubble(R.string.velo_customize_sample_out, colours[SENT], Gravity.END, size, SENT));
        final android.widget.ImageView button = new android.widget.ImageView(this);
        button.setImageResource(R.drawable.ic_send_24dp);
        button.setColorFilter(VeloThemes.onColor(colours[BUTTONS]));
        final int inset = dp(10);
        button.setPadding(inset, inset, inset, inset);
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(colours[BUTTONS]);
        if (part == BUTTONS && tab == TAB_COLOURS) {
            circle.setStroke(dp(3), VeloThemes.onColor(colours[WALL]));
        }
        button.setBackground(circle);
        button.setContentDescription(getString(R.string.velo_customize_part_buttons));
        button.setOnClickListener(v -> pick(BUTTONS));
        final LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        buttonParams.gravity = Gravity.END;
        buttonParams.topMargin = dp(6);
        wall.addView(button, buttonParams);
        card.addView(wall, new LinearLayout.LayoutParams(-1, -2));
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));
        final TextView hint = new TextView(this);
        hint.setText(tab == TAB_COLOURS ? R.string.velo_customize_hint_colours : R.string.velo_customize_hint);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(on, surface, 0.32f));
        hint.setPadding(dp(4), dp(8), dp(4), dp(4));
        box.addView(hint);
        return box;
    }

    private TextView bubble(final int name, final int colour, final int side, final int size, final int which) {
        final TextView bubble = new TextView(this);
        bubble.setText(name);
        bubble.setTextSize(size);
        bubble.setTextColor(VeloThemes.onColor(colour));
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        final int corner = dp(Math.max(VeloThemes.corners(this), 2));
        final GradientDrawable drawable = rounded(colour, corner);
        if (part == which && tab == TAB_COLOURS) {
            drawable.setStroke(dp(3), VeloThemes.onColor(colour));
        }
        bubble.setBackground(drawable);
        bubble.setContentDescription(getString(which == SENT ? R.string.velo_customize_part_sent : R.string.velo_customize_part_received));
        bubble.setOnClickListener(v -> pick(which));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = side;
        params.bottomMargin = dp(6);
        bubble.setLayoutParams(params);
        return bubble;
    }

    private void pick(final int which) {
        part = which;
        tab = TAB_COLOURS;
        render();
    }

    private View buildTabs() {
        final LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(14), dp(4), dp(14), dp(8));
        final int[] names = {R.string.velo_customize_tab_colours, R.string.velo_customize_tab_wall,
            R.string.velo_customize_tab_shapes, R.string.velo_customize_tab_text, R.string.velo_customize_tab_bar};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            final TextView button = chip(names[i], tab == i);
            button.setOnClickListener(v -> {
                tab = index;
                if (index == TAB_WALL) {
                    part = WALL;
                }
                render();
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
            params.setMarginEnd(i < names.length - 1 ? dp(4) : 0);
            row.addView(button, params);
        }
        return row;
    }

    private TextView chip(final int name, final boolean selected) {
        return chip(getString(name), selected);
    }

    private TextView chip(final String name, final boolean selected) {
        final TextView chip = new TextView(this);
        chip.setText(name);
        chip.setTextSize(13);
        chip.setGravity(Gravity.CENTER);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setTextColor(selected ? VeloThemes.onColor(colours[BUTTONS]) : text);
        chip.setBackground(rounded(selected ? colours[BUTTONS] : surfaceHigh, dp(14)));
        chip.setPadding(dp(8), 0, dp(8), 0);
        return chip;
    }

    private void section(final int name) {
        final TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(13);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(on, surface, 0.25f));
        title.setPadding(dp(4), dp(10), dp(4), dp(6));
        body.addView(title);
    }

    private GridLayout grid(final int columns) {
        final GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        body.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        return grid;
    }

    private void addCell(final GridLayout grid, final View view, final int height) {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        params.width = 0;
        params.height = height;
        final int margin = dp(3);
        params.setMargins(margin, margin, margin, margin);
        grid.addView(view, params);
    }

    private void addSwatches(final int which) {
        final GridLayout swatches = grid(8);
        for (final int colour : SWATCHES) {
            final View swatch = new View(this);
            final GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(colour);
            drawable.setStroke(colours[which] == colour ? dp(3) : dp(1),
                    colours[which] == colour ? on : androidx.core.graphics.ColorUtils.setAlphaComponent(on, 0x30));
            swatch.setBackground(drawable);
            swatch.setContentDescription(String.format("#%06X", colour & 0xFFFFFF));
            swatch.setOnClickListener(v -> setColour(which, colour));
            // Square cells, so the circles stay round on any screen width
            final int side = (getResources().getDisplayMetrics().widthPixels - dp(28) - dp(6) * 8) / 8;
            final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = side;
            params.height = side;
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            swatches.addView(swatch, params);
        }
    }

    private void setColour(final int which, final int colour) {
        colours[which] = colour;
        persist();
        render();
    }

    private void persist() {
        VeloThemes.applyColours(this, colours[BAR], colours[BUTTONS], colours[SENT], colours[RECEIVED], colours[WALL], colours[PAGE]);
    }

    private void fillColours() {
        if (!VeloThemes.customColorsAvailable()) {
            final TextView note = new TextView(this);
            note.setText(R.string.velo_customize_no_colours);
            note.setTextColor(text);
            body.addView(note);
            return;
        }
        final GridLayout parts = grid(3);
        for (int i = 0; i < PART_NAMES.length; i++) {
            final int which = i;
            final TextView chip = chip(PART_NAMES[i], part == i);
            chip.setOnClickListener(v -> {
                part = which;
                render();
            });
            addCell(parts, chip, dp(44));
        }
        section(R.string.velo_customize_pick);
        addSwatches(part);
        final TextView free = chip(R.string.velo_customize_free_colour, false);
        free.setOnClickListener(v -> pickFree());
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(10);
        body.addView(free, params);
    }

    private void pickFree() {
        final ColorPickerView picker = new ColorPickerView(this);
        picker.showAlpha(false);
        picker.showHex(false);
        picker.setColor(colours[part]);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(PART_NAMES[part])
                .setView(picker)
                .setPositiveButton(android.R.string.ok, (d, w) -> setColour(part, picker.getColor() | 0xFF000000))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fillWall() {
        section(R.string.velo_customize_wall_style);
        final GridLayout walls = grid(3);
        final String current = prefs.getString(VeloThemes.CHAT_PATTERN, VeloThemes.PATTERN_DRAWINGS);
        final boolean hasPhoto = globalPhoto().exists();
        // Velo 0.32.1: every background as a small preview in the chosen colours
        final java.util.List<String> keys = new java.util.ArrayList<>();
        final java.util.List<Integer> names = new java.util.ArrayList<>();
        for (final VeloThemes.Wallpaper wallpaper : VeloThemes.WALLPAPERS) {
            keys.add(wallpaper.key);
            names.add(wallpaper.nameRes);
        }
        keys.add(VeloThemes.PATTERN_PLAIN);
        names.add(R.string.velo_customize_wall_plain);
        keys.add(VeloThemes.PATTERN_GRADIENT);
        names.add(R.string.velo_customize_wall_gradient);
        keys.add(VeloThemes.PATTERN_GLOW);
        names.add(R.string.velo_customize_wall_glow);
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            final View preview = wallPreview(names.get(i), key, !hasPhoto && key.equals(current));
            preview.setOnClickListener(v -> {
                prefs.edit().putString(VeloThemes.CHAT_PATTERN, key).apply();
                globalPhoto().delete();
                render();
            });
            addCell(walls, preview, dp(76));
        }
        final TextView photo = chip(R.string.velo_customize_wall_photo, hasPhoto);
        photo.setOnClickListener(v -> {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(Intent.createChooser(intent, getString(R.string.velo_customize_wall_photo)),
                    ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND);
        });
        addCell(walls, photo, dp(56));
        if (VeloThemes.customColorsAvailable()) {
            section(R.string.velo_customize_part_wall);
            addSwatches(WALL);
        }
    }

    private View wallPreview(final int name, final String key, final boolean selected) {
        final TextView cell = new TextView(this);
        cell.setText(name);
        cell.setTextSize(12);
        cell.setTypeface(cell.getTypeface(), android.graphics.Typeface.BOLD);
        cell.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        cell.setPadding(dp(4), 0, dp(4), dp(6));
        cell.setTextColor(VeloThemes.onColor(colours[WALL]));
        cell.setBackground(VeloThemes.chatBackgroundDrawable(this, colours[WALL], key, colours[BUTTONS]));
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setStroke(selected ? dp(3) : dp(1), selected ? colours[BUTTONS]
                : androidx.core.graphics.ColorUtils.blendARGB(colours[WALL], VeloThemes.onColor(colours[WALL]), 0.2f));
        cell.setForeground(ring);
        cell.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        cell.setClipToOutline(true);
        cell.setContentDescription(getString(name));
        return cell;
    }

    private File globalPhoto() {
        return ChatBackgroundHelper.getBgFile(this, null);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, @Nullable final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND) {
            ChatBackgroundHelper.onActivityResult(this, requestCode, resultCode, data, null);
            render();
        }
    }

    private void fillShapes() {
        section(R.string.velo_customize_corners);
        final GridLayout corners = grid(4);
        final int[] values = {4, 10, 18, 26};
        final int[] cornerNames = {R.string.velo_customize_corners_square, R.string.velo_customize_corners_light,
            R.string.velo_customize_corners_round, R.string.velo_customize_corners_full};
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            final TextView chip = chip(cornerNames[i], VeloThemes.corners(this) == value);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt(VeloThemes.CORNERS, value).apply();
                render();
            });
            addCell(corners, chip, dp(56));
        }
        section(R.string.velo_customize_avatars);
        final GridLayout avatars = grid(3);
        final String[] shapes = {"oval", "rounded_square", "square"};
        final int[] shapeNames = {R.string.velo_customize_avatar_round, R.string.velo_customize_avatar_soft, R.string.velo_customize_avatar_square};
        final String current = prefs.getString("avatar_shape", getString(R.string.avatar_shape));
        for (int i = 0; i < shapes.length; i++) {
            final String shape = shapes[i];
            final TextView chip = chip(shapeNames[i], shape.equals(current));
            chip.setOnClickListener(v -> {
                prefs.edit().putString("avatar_shape", shape).apply();
                render();
            });
            addCell(avatars, chip, dp(56));
        }
    }

    private void fillText() {
        section(R.string.velo_customize_text_size);
        final GridLayout sizes = grid(4);
        for (final int value : new int[] {12, 14, 16, 19}) {
            final TextView chip = chip("Aa", VeloThemes.textSize(this) == value);
            chip.setTextSize(value);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt(VeloThemes.TEXT_SIZE, value).apply();
                render();
            });
            addCell(sizes, chip, dp(56));
        }
        section(R.string.velo_customize_font);
        final String[] keys = {VeloFonts.MODERN, VeloFonts.ROUNDED, VeloFonts.ELEGANT};
        final int[] names = {R.string.velo_customize_font_modern, R.string.velo_customize_font_rounded, R.string.velo_customize_font_elegant};
        final int[] faces = {R.font.noto_sans_regular, R.font.velo_nunito_regular, R.font.velo_lora_regular};
        final String current = VeloFonts.current(this);
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            final TextView chip = chip(names[i], key.equals(current));
            chip.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, faces[i]));
            chip.setTextSize(16);
            chip.setOnClickListener(v -> {
                prefs.edit().putString(VeloThemes.FONT, key).apply();
                render();
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
            params.bottomMargin = dp(6);
            body.addView(chip, params);
        }
    }

    /** Velo 0.45: the real bottom bar, styled as chosen, on a page of the chosen colour. */
    private View barPreview() {
        final FrameLayout frame = new FrameLayout(this);
        frame.setClipToOutline(true);
        frame.setBackground(rounded(androidx.core.graphics.ColorUtils.blendARGB(surface, on, 0.04f), dp(20)));
        final com.google.android.material.bottomnavigation.BottomNavigationView bar =
                new com.google.android.material.bottomnavigation.BottomNavigationView(this);
        bar.inflateMenu(R.menu.bottom_navigation_menu_chat);
        bar.setSelectedItemId(R.id.chats);
        frame.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        VeloBottomBar.style(bar);
        return frame;
    }

    private void choices(final int title, final String key, final String current, final String[] values, final int[] names) {
        section(title);
        final GridLayout grid = grid(values.length);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            final TextView chip = chip(names[i], value.equals(current));
            chip.setOnClickListener(v -> {
                prefs.edit().putString(key, value).apply();
                render();
            });
            addCell(grid, chip, dp(52));
        }
    }

    /** A colour of the bar: "as the theme" (0) or one of the swatches. */
    private void barColour(final int title, final String key) {
        section(title);
        final int current = prefs.getInt(key, 0);
        final TextView theme = chip(R.string.velo_bar_theme_colour, current == 0);
        theme.setOnClickListener(v -> {
            prefs.edit().remove(key).apply();
            render();
        });
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.bottomMargin = dp(4);
        body.addView(theme, params);
        final GridLayout swatches = grid(8);
        for (final int colour : SWATCHES) {
            final View swatch = new View(this);
            final GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(colour);
            drawable.setStroke(current == colour ? dp(3) : dp(1),
                    current == colour ? on : androidx.core.graphics.ColorUtils.setAlphaComponent(on, 0x30));
            swatch.setBackground(drawable);
            swatch.setContentDescription(String.format("#%06X", colour & 0xFFFFFF));
            swatch.setOnClickListener(v -> {
                prefs.edit().putInt(key, colour).apply();
                render();
            });
            final int side = (getResources().getDisplayMetrics().widthPixels - dp(28) - dp(6) * 8) / 8;
            final GridLayout.LayoutParams cell = new GridLayout.LayoutParams();
            cell.width = side;
            cell.height = side;
            cell.setMargins(dp(3), dp(3), dp(3), dp(3));
            swatches.addView(swatch, cell);
        }
    }

    /** Velo 0.45: Personalizza, Barra - size, shape, labels and colours of the bottom bar. */
    private void fillBar() {
        choices(R.string.velo_bar_size, VeloBottomBar.SIZE, VeloBottomBar.size(this),
                new String[] {VeloBottomBar.SIZE_SMALL, VeloBottomBar.SIZE_NORMAL, VeloBottomBar.SIZE_LARGE},
                new int[] {R.string.velo_bar_size_small, R.string.velo_bar_size_normal, R.string.velo_bar_size_large});
        choices(R.string.velo_bar_shape, VeloBottomBar.SHAPE, VeloBottomBar.shape(this),
                new String[] {VeloBottomBar.SHAPE_PILL, VeloBottomBar.SHAPE_FULL},
                new int[] {R.string.velo_bar_shape_pill, R.string.velo_bar_shape_full});
        choices(R.string.velo_bar_labels, VeloBottomBar.LABELS, VeloBottomBar.labels(this),
                new String[] {VeloBottomBar.LABELS_ALWAYS, VeloBottomBar.LABELS_SELECTED, VeloBottomBar.LABELS_NEVER},
                new int[] {R.string.velo_bar_labels_always, R.string.velo_bar_labels_selected, R.string.velo_bar_labels_never});
        barColour(R.string.velo_bar_accent, VeloBottomBar.ACCENT);
        barColour(R.string.velo_bar_background, VeloBottomBar.BACKGROUND);
    }

    private void reset() {
        globalPhoto().delete();
        final SharedPreferences.Editor editor = prefs.edit().remove(VeloThemes.CHAT_PATTERN).remove(VeloThemes.FONT)
                .remove("avatar_shape").remove(VeloThemes.TEXT_SIZE).remove(VeloThemes.CORNERS);
        for (final String key : VeloBottomBar.KEYS) {
            editor.remove(key);
        }
        editor.commit();
        VeloThemes.apply(this, VeloThemes.CLASSICO, null, false);
        Toast.makeText(this, R.string.velo_customize_reset_done, Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void saveAsMine() {
        final JSONObject saved = new JSONObject();
        try {
            final String[] names = {"sent", "received", "bar", "buttons", "wall", "page"};
            for (int i = 0; i < names.length; i++) {
                saved.put(names[i], colours[i]);
            }
            saved.put("pattern", prefs.getString(VeloThemes.CHAT_PATTERN, VeloThemes.PATTERN_DRAWINGS));
            saved.put("font", VeloFonts.current(this));
            saved.put("avatar", prefs.getString("avatar_shape", getString(R.string.avatar_shape)));
            saved.put("size", VeloThemes.textSize(this));
            saved.put("corners", VeloThemes.corners(this));
            // Velo 0.45: the bottom bar is part of one's own theme too
            saved.put("nav_size", VeloBottomBar.size(this));
            saved.put("nav_shape", VeloBottomBar.shape(this));
            saved.put("nav_labels", VeloBottomBar.labels(this));
            saved.put("nav_accent", prefs.getInt(VeloBottomBar.ACCENT, 0));
            saved.put("nav_background", prefs.getInt(VeloBottomBar.BACKGROUND, 0));
        } catch (final JSONException e) {
            return;
        }
        persist();
        prefs.edit().putString(VeloThemes.SAVED, saved.toString()).apply();
        Toast.makeText(this, R.string.velo_customize_saved, Toast.LENGTH_LONG).show();
    }

    public static boolean hasSaved(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).contains(VeloThemes.SAVED);
    }

    /** A colour of the saved theme: "wall", "received" or "sent". */
    public static int savedColour(final Context context, final String part) {
        try {
            return new JSONObject(PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(VeloThemes.SAVED, "{}")).getInt(part);
        } catch (final JSONException e) {
            return 0xFFBFD8EE;
        }
    }

    public static void applySaved(final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            final JSONObject saved = new JSONObject(prefs.getString(VeloThemes.SAVED, "{}"));
            VeloThemes.applyColours(context, saved.getInt("bar"), saved.getInt("buttons"), saved.getInt("sent"),
                    saved.getInt("received"), saved.getInt("wall"), saved.getInt("page"));
            prefs.edit().putString(VeloThemes.CHAT_PATTERN, saved.getString("pattern"))
                    .putString(VeloThemes.FONT, saved.getString("font"))
                    .putString("avatar_shape", saved.getString("avatar"))
                    .putInt(VeloThemes.TEXT_SIZE, saved.getInt("size"))
                    .putInt(VeloThemes.CORNERS, saved.getInt("corners"))
                    // themes saved before 0.45 have no bar: it goes back to how it comes
                    .putString(VeloBottomBar.SIZE, saved.optString("nav_size", VeloBottomBar.SIZE_NORMAL))
                    .putString(VeloBottomBar.SHAPE, saved.optString("nav_shape", VeloBottomBar.SHAPE_PILL))
                    .putString(VeloBottomBar.LABELS, saved.optString("nav_labels", VeloBottomBar.LABELS_ALWAYS))
                    .putInt(VeloBottomBar.ACCENT, saved.optInt("nav_accent", 0))
                    .putInt(VeloBottomBar.BACKGROUND, saved.optInt("nav_background", 0)).commit();
        } catch (final JSONException ignored) {
            // a saved theme is written whole by saveAsMine; nothing to apply otherwise
        }
    }
}
