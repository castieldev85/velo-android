package eu.siacs.conversations.velo;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.XmppConnectionService;

/**
 * Velo 0.55: the first time the chat list opens with an account, three short cards explain the
 * basics (testers found Velo "not intuitive" at first). Only for people new to Velo: whoever has
 * already seen a "what's new" window is not new, and never gets them. Skip ends them at once; they
 * never come back.
 */
public final class VeloIntro {

    static final String DONE = "velo_intro_done";

    private static final int[] TITLES = {
        R.string.velo_intro_1_title, R.string.velo_intro_2_title, R.string.velo_intro_3_title
    };
    private static final int[] TEXTS = {
        R.string.velo_intro_1_text, R.string.velo_intro_2_text, R.string.velo_intro_3_text
    };
    private static final int[] ICONS = {
        R.drawable.main_logo, R.drawable.ic_forum_24dp, R.drawable.rounded_account_circle_24
    };

    private static boolean open = false;

    private VeloIntro() {}

    public static boolean isOpen() {
        return open;
    }

    public static void maybeShow(final Activity activity, final XmppConnectionService service) {
        if (open || activity.isFinishing() || service == null || VeloUpdater.account(service) == null) {
            return;
        }
        final SharedPreferences preferences = service.getPreferences();
        if (preferences.getBoolean(DONE, false)) {
            return;
        }
        if (preferences.getInt(VeloWhatsNew.SEEN, 0) > 0) {
            // already used Velo before this version: not new
            preferences.edit().putBoolean(DONE, true).apply();
            return;
        }
        open = true;
        final View view = LayoutInflater.from(activity).inflate(R.layout.velo_intro, null);
        final ImageView icon = view.findViewById(R.id.velo_intro_icon);
        final TextView title = view.findViewById(R.id.velo_intro_title);
        final TextView text = view.findViewById(R.id.velo_intro_text);
        final TextView dots = view.findViewById(R.id.velo_intro_dots);
        final int[] page = {0};
        final android.content.res.ColorStateList tint =
                android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(
                                view, androidx.appcompat.R.attr.colorPrimary));
        final Runnable finish =
                () ->
                        preferences
                                .edit()
                                .putBoolean(DONE, true)
                                // a new user does not need the list of changes of this version
                                .putInt(VeloWhatsNew.SEEN, BuildConfig.VERSION_CODE)
                                .apply();
        final AlertDialog dialog =
                new MaterialAlertDialogBuilder(activity)
                        .setView(view)
                        .setCancelable(false)
                        .setPositiveButton(R.string.velo_intro_next, null)
                        .setNegativeButton(R.string.velo_intro_skip, (d, w) -> finish.run())
                        .setOnDismissListener(d -> open = false)
                        .create();
        final Runnable show =
                () -> {
                    final int p = page[0];
                    icon.setImageResource(ICONS[p]);
                    // the Velo logo keeps its own colours, the other icons take the theme colour
                    androidx.core.widget.ImageViewCompat.setImageTintList(icon, p == 0 ? null : tint);
                    title.setText(TITLES[p]);
                    text.setText(TEXTS[p]);
                    dots.setText(dots(p));
                    final boolean last = p == TITLES.length - 1;
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setText(last ? R.string.velo_intro_start : R.string.velo_intro_next);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                            .setVisibility(last ? View.GONE : View.VISIBLE);
                };
        dialog.setOnShowListener(
                d -> {
                    show.run();
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(
                                    v -> {
                                        if (page[0] < TITLES.length - 1) {
                                            page[0]++;
                                            show.run();
                                        } else {
                                            finish.run();
                                            dialog.dismiss();
                                        }
                                    });
                });
        dialog.show();
    }

    /** "● ○ ○" for the first page, and so on. */
    static String dots(final int page) {
        final StringBuilder dots = new StringBuilder();
        for (int i = 0; i < TITLES.length; i++) {
            if (i > 0) {
                dots.append(' ');
            }
            dots.append(i == page ? '●' : '○');
        }
        return dots.toString();
    }
}
