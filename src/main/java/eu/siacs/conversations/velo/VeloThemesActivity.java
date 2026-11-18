package eu.siacs.conversations.velo;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.BaseActivity;
import eu.siacs.conversations.utils.ThemeHelper;

/** Velo: "Aspetto", one page: ready-made themes, the way to Personalizza, chat list, app icon, keyboard. */
public class VeloThemesActivity extends BaseActivity {

    private VeloThemes.Preset preset;
    @Nullable private Integer accent;
    private boolean autoNight;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.applyCustomColors(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_velo_themes);
        setSupportActionBar(findViewById(R.id.velo_themes_toolbar));
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.velo_themes_toolbar))
                .setNavigationOnClickListener(v -> finish());
        Activities.setStatusAndNavigationBarColors(this, findViewById(R.id.velo_themes_root));

        preset = VeloThemes.current(this);
        accent = VeloThemes.currentAccent(this);
        autoNight = VeloThemes.autoNight(this);

        buildPresets();
        addMyTheme();
        updatePreview();
        veloCustomizeRow();
        veloChatSettings();

        findViewById(R.id.velo_custom_unavailable).setVisibility(
                VeloThemes.customColorsAvailable() ? View.GONE : View.VISIBLE);

        final MaterialSwitch nightSwitch = findViewById(R.id.velo_auto_night);
        nightSwitch.setChecked(autoNight);
        nightSwitch.setOnCheckedChangeListener((button, checked) -> {
            autoNight = checked;
            applyAndRecreate();
        });
    }

    private void veloCustomizeRow() {
        final com.google.android.material.button.MaterialButton row = findViewById(R.id.velo_customize);
        row.setIconTint(null);
        row.setIcon(VeloUi.settingsIcon(this, androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_palette_24dp),
                VeloUi.COLOUR_MEDIA));
        final String title = getString(R.string.velo_customize_title);
        final android.text.SpannableString text = new android.text.SpannableString(
                title + "\n" + getString(R.string.velo_customize_summary));
        text.setSpan(new android.text.style.RelativeSizeSpan(0.8f), title.length(), text.length(), 0);
        row.setText(text);
        row.setOnClickListener(v -> startActivity(new android.content.Intent(this, VeloCustomizeActivity.class)));
    }

    // Velo 0.28: back from Personalizza with new colours
    private boolean veloLeftForCustom = false;

    @Override
    protected void onStop() {
        super.onStop();
        veloLeftForCustom = true;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (veloLeftForCustom) {
            veloLeftForCustom = false;
            recreate();
        }
    }

    private void applyAndRecreate() {
        VeloThemes.apply(this, preset, accent, autoNight);
        recreate();
    }

    // Velo 0.28: the theme saved in Personalizza, next to the ready-made ones
    private void addMyTheme() {
        if (!VeloCustomizeActivity.hasSaved(this)) {
            return;
        }
        final LinearLayout container = findViewById(R.id.velo_presets);
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(6), 0, dp(6), 0);
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        final GradientDrawable cardBg = rounded(VeloCustomizeActivity.savedColour(this, "wall"), dp(12));
        cardBg.setStroke(dp(1), MaterialColors.getColor(container, com.google.android.material.R.attr.colorOutline));
        card.setBackground(cardBg);
        card.addView(bar(VeloCustomizeActivity.savedColour(this, "received"), Gravity.START));
        card.addView(bar(VeloCustomizeActivity.savedColour(this, "sent"), Gravity.END));
        item.addView(card, new LinearLayout.LayoutParams(dp(62), dp(84)));
        final TextView label = new TextView(this);
        label.setText(R.string.velo_customize_my_theme);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(0, dp(6), 0, 0);
        item.addView(label);
        item.setOnClickListener(v -> {
            VeloCustomizeActivity.applySaved(this);
            recreate();
        });
        container.addView(item);
    }

    private void buildPresets() {
        final LinearLayout container = findViewById(R.id.velo_presets);
        final int ring = MaterialColors.getColor(container, androidx.appcompat.R.attr.colorPrimary);
        final int line = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOutline);
        for (final VeloThemes.Preset p : VeloThemes.PRESETS) {
            final boolean available = VeloThemes.customColorsAvailable()
                    || p == VeloThemes.CLASSICO || p == VeloThemes.NOTTE;
            if (!available) {
                continue;
            }
            final LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(6), 0, dp(6), 0);

            final LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            final GradientDrawable cardBg = rounded(p.chatBg, dp(12));
            final boolean chosen = p == preset && !PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(VeloThemes.HAND_MADE, false);
            cardBg.setStroke(chosen ? dp(3) : dp(1), chosen ? ring : line);
            card.setBackground(cardBg);
            card.addView(bar(p.bubbleIn, Gravity.START));
            card.addView(bar(p.bubbleOut(null), Gravity.END));
            item.addView(card, new LinearLayout.LayoutParams(dp(62), dp(84)));

            final TextView label = new TextView(this);
            label.setText(p.nameRes);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setPadding(0, dp(6), 0, 0);
            item.addView(label);

            item.setContentDescription(getString(p.nameRes));
            item.setOnClickListener(v -> {
                preset = p;
                accent = null;
                applyAndRecreate();
            });
            container.addView(item);
            if (chosen) {
                // Velo 0.32: with many themes the chosen one may be off screen
                container.post(() -> ((View) container.getParent()).scrollTo(Math.max(0, item.getLeft() - dp(16)), 0));
            }
        }
    }

    private View bar(final int color, final int gravity) {
        final View bar = new View(this);
        bar.setBackground(rounded(color, dp(6)));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(12));
        params.gravity = gravity;
        params.topMargin = dp(3);
        params.bottomMargin = dp(3);
        bar.setLayoutParams(params);
        return bar;
    }

    private void updatePreview() {
        final boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        final View preview = findViewById(R.id.velo_preview);
        preview.setBackground(rounded(VeloThemes.chatBackground(this, night), dp(14)));
        final TextView in = findViewById(R.id.velo_preview_in);
        final TextView out = findViewById(R.id.velo_preview_out);
        // The bubbles show what the chat will use: the theme's containers.
        final int inColor = MaterialColors.getColor(in, com.google.android.material.R.attr.colorSecondaryContainer);
        final int outColor = MaterialColors.getColor(out, com.google.android.material.R.attr.colorPrimaryContainer);
        final int corners = VeloThemes.corners(this);
        final int textSize = VeloThemes.textSize(this);
        in.setBackground(rounded(inColor, dp(corners)));
        in.setTextColor(VeloThemes.onColor(inColor));
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
        out.setBackground(rounded(outColor, dp(corners)));
        out.setTextColor(VeloThemes.onColor(outColor));
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
    }

    private GradientDrawable rounded(final int color, final int radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(ColorStateList.valueOf(color));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // Velo 0.19: chat list rows and app icon; Velo 0.28: the keyboard option, here too
    private void veloChatSettings() {
        final android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        final android.widget.RadioGroup lines = findViewById(R.id.velo_list_lines);
        lines.check(VeloThemes.listLines(this) == 3 ? R.id.velo_list_three : R.id.velo_list_two);
        lines.setOnCheckedChangeListener((group, id) ->
                prefs.edit().putInt(VeloThemes.LIST_LINES, id == R.id.velo_list_three ? 3 : 2).apply());

        final MaterialSwitch enterIsSend = findViewById(R.id.velo_enter_is_send);
        enterIsSend.setChecked(prefs.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send)));
        enterIsSend.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean("enter_is_send", checked).apply());

        final LinearLayout icons = findViewById(R.id.velo_icons);
        icons.removeAllViews();
        final int current = VeloIcons.current(this);
        for (int i = 0; i < VeloIcons.CHOICES.length; i++) {
            final int choice = i;
            final LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(6), dp(4), dp(6));
            final android.widget.ImageView image = new android.widget.ImageView(this);
            image.setImageResource(VeloIcons.CHOICES[i].icon);
            final int size = dp(56);
            final LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(size, size);
            image.setLayoutParams(imageParams);
            image.setPadding(dp(3), dp(3), dp(3), dp(3));
            if (i == current) {
                final GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setStroke(dp(3), MaterialColors.getColor(image, androidx.appcompat.R.attr.colorPrimary));
                image.setBackground(ring);
            }
            cell.addView(image);
            final TextView label = new TextView(this);
            label.setText(VeloIcons.CHOICES[i].label);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(6), 0, 0);
            if (i == current) {
                label.setTextColor(MaterialColors.getColor(label, androidx.appcompat.R.attr.colorPrimary));
            }
            cell.addView(label);
            final LinearLayout.LayoutParams cellParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(cellParams);
            cell.setOnClickListener(v -> {
                if (choice == VeloIcons.current(this)) {
                    return;
                }
                VeloIcons.apply(this, choice);
                Toast.makeText(this, R.string.velo_icon_changed, Toast.LENGTH_LONG).show();
                veloChatSettings();
            });
            icons.addView(cell);
        }
    }
}
