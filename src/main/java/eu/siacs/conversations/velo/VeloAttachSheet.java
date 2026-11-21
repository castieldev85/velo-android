package eu.siacs.conversations.velo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import eu.siacs.conversations.R;

/** Velo 0.28: the paper clip's sheet, a grid of coloured circles. */
public final class VeloAttachSheet {

    private VeloAttachSheet() {}

    public static void show(final Context context, final Runnable photo, final Runnable camera, final Runnable video,
            final Runnable file, final Runnable place, final Runnable livePlace, final Runnable dictate) {
        final BottomSheetDialog dialog = new BottomSheetDialog(context);
        final float density = context.getResources().getDisplayMetrics().density;
        final GridLayout grid = new GridLayout(context);
        grid.setColumnCount(4);
        final int pad = Math.round(14 * density);
        grid.setPadding(pad, pad, pad, pad * 2);
        add(dialog, grid, density, R.string.velo_attach_photo, R.drawable.ic_image_24dp, 0xFF7E48CC, photo);
        add(dialog, grid, density, R.string.velo_attach_camera, R.drawable.ic_camera_alt_24dp, 0xFF1F6FD6, camera);
        add(dialog, grid, density, R.string.velo_attach_video, R.drawable.ic_videocam_24dp, 0xFFBF3B78, video);
        add(dialog, grid, density, R.string.velo_attach_file, R.drawable.ic_description_24dp, 0xFFC2591A, file);
        add(dialog, grid, density, R.string.velo_attach_place, R.drawable.ic_location_pin_24dp, 0xFF1F8A4D, place);
        add(dialog, grid, density, R.string.velo_attach_live_place, R.drawable.ic_location_pin_24dp, 0xFF0E8078, livePlace);
        add(dialog, grid, density, R.string.velo_dictation, R.drawable.ic_dictation_24, 0xFF4A6078, dictate);
        dialog.setContentView(grid);
        dialog.show();
        matchNavigationBar(dialog);
    }

    /**
     * Velo 0.46.6: the strip of the system buttons under the sheet was black (the app theme's
     * navigationBarColor), a dark band under a light sheet. It takes the sheet's colour now, with
     * icons that read on it.
     */
    private static void matchNavigationBar(final BottomSheetDialog dialog) {
        final android.view.Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        final int sheet = com.google.android.material.color.MaterialColors.getColor(dialog.getContext(),
                com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFFFFFFFF);
        window.setNavigationBarColor(sheet);
        androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(VeloThemes.onColor(sheet) != 0xFFFFFFFF);
    }

    private static void add(final BottomSheetDialog dialog, final GridLayout grid, final float density, final int name,
            final int icon, final int colour, final Runnable action) {
        final Context context = grid.getContext();
        final LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setPadding(Math.round(2 * density), Math.round(8 * density), Math.round(2 * density), Math.round(8 * density));
        final ImageView circle = new ImageView(context);
        final GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(colour);
        circle.setBackground(background);
        circle.setImageResource(icon);
        circle.setColorFilter(0xFFFFFFFF);
        final int inset = Math.round(15 * density);
        circle.setPadding(inset, inset, inset, inset);
        final int size = Math.round(56 * density);
        cell.addView(circle, new LinearLayout.LayoutParams(size, size));
        final TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Math.round(6 * density), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
        cell.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        params.width = 0;
        grid.addView(cell, params);
    }
}
