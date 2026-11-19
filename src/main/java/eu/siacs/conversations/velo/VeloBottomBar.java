package eu.siacs.conversations.velo;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.XmppActivity;

/**
 * Velo 0.44: the bottom bar of the new Telegram - Chats, Contacts, Settings and Profile, the last
 * one with the profile's own picture. It replaces the side menu.
 *
 * <p>Only Chats is ever the selected tab: the other three open their own screen and the bar comes
 * back on Chats. The selected tab sits on a light pill in the theme colour, as in Telegram.
 */
public final class VeloBottomBar {

    private static final ExecutorService AVATARS = Executors.newSingleThreadExecutor();

    private VeloBottomBar() {}

    /**
     * Velo 0.44.1: up to 0.27 the Interface settings carried two hidden switches, and opening that
     * page was enough for Android to store their values then - bottom bar off, side menu on. A
     * stored value beats the new default, so on those phones 0.44.0 still showed the side menu.
     * Velo has no switch for either any more: the stored values go, once.
     */
    public static void forgetOldNavigationSettings(final android.content.SharedPreferences preferences) {
        if (preferences == null || preferences.getBoolean("velo_nav_044", false)) {
            return;
        }
        preferences
                .edit()
                .remove("show_nav_bar")
                .remove("show_nav_drawer")
                .putBoolean("velo_nav_044", true)
                .apply();
    }

    /** Size, colours and labels of the bar. Once, when the screen is created. */
    public static void style(final BottomNavigationView bar) {
        if (bar == null) {
            return;
        }
        final float density = bar.getResources().getDisplayMetrics().density;
        final ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            // four labels need almost the whole width; the bottom margin is FloatingBars' business
            margins.leftMargin = Math.round(12 * density);
            margins.rightMargin = Math.round(12 * density);
            margins.setMarginStart(margins.leftMargin);
            margins.setMarginEnd(margins.rightMargin);
            margins.height = Math.round(72 * density);
            bar.setLayoutParams(margins);
        }
        final int primary = MaterialColors.getColor(bar, androidx.appcompat.R.attr.colorPrimary);
        final int normal = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorOnSurface);
        final ColorStateList tint =
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {primary, normal});
        bar.setItemIconTintList(tint);
        bar.setItemTextColor(tint);
        bar.setItemActiveIndicatorEnabled(false);
        // a resource rather than a drawable: every tab needs its own copy of a stateful background
        bar.setItemBackgroundResource(R.drawable.velo_nav_item_background);
        bar.setItemIconSize(Math.round(27 * density));
        bar.setItemPaddingTop(Math.round(9 * density));
        bar.setItemPaddingBottom(Math.round(6 * density));
        bar.setActiveIndicatorLabelPadding(Math.round(3 * density));
        // the label size comes from TextAppearance.App.NavigationBarLabel (velo_bottom_bar.xml)
        bar.setItemTextAppearanceActiveBoldEnabled(true);
        bar.setLabelVisibilityMode(BottomNavigationView.LABEL_VISIBILITY_LABELED);
        // Telegram's bar: a full pill, nearly opaque, on its shadow
        final android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(35 * density);
        final int surface = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorSurface);
        pill.setColor(surface);
        // on a white list the white pill needs an edge to be seen at all
        pill.setStroke(
                Math.max(1, Math.round(density)),
                MaterialColors.getColor(bar, com.google.android.material.R.attr.colorOutlineVariant));
        bar.setBackground(pill);
    }

    /**
     * Puts {@code account}'s picture on the Profile tab. Cheap to call on every refresh: nothing
     * changes, and nothing is laid out again, unless the profile or its picture did.
     */
    public static void showAvatar(
            final XmppActivity activity, final BottomNavigationView bar, @Nullable final Account account) {
        if (activity == null || bar == null || activity.xmppConnectionService == null) {
            return;
        }
        final MenuItem item = bar.getMenu().findItem(R.id.velo_nav_profile);
        if (item == null) {
            return;
        }
        final String key = account == null ? "" : account.getUuid() + "|" + account.getAvatar();
        if (Objects.equals(bar.getTag(R.id.velo_nav_profile), key)) {
            return;
        }
        if (account == null) {
            bar.setTag(R.id.velo_nav_profile, key);
            item.setIcon(R.drawable.rounded_account_circle_24);
            return;
        }
        final int size = Math.round(27 * activity.getResources().getDisplayMetrics().density);
        final Drawable cached = activity.xmppConnectionService.getAvatarService().get(account, size, true);
        if (cached == null) {
            // not drawn yet: draw it away from the screen, then come back here
            AVATARS.execute(
                    () -> {
                        if (activity.xmppConnectionService == null) {
                            return;
                        }
                        activity.xmppConnectionService.getAvatarService().get(account, size, false);
                        activity.runOnUiThread(() -> showAvatar(activity, bar, account));
                    });
            return;
        }
        final Bitmap bitmap = FileBackend.drawDrawable(cached);
        if (bitmap == null) {
            return;
        }
        final RoundedBitmapDrawable round = RoundedBitmapDrawableFactory.create(activity.getResources(), bitmap);
        round.setCircular(true);
        round.setAntiAlias(true);
        bar.setTag(R.id.velo_nav_profile, key);
        item.setIcon(new Untinted(round));
    }

    /**
     * Telegram's account switcher: every profile, "All accounts" when there is more than one,
     * then "Manage accounts" and "Add account". {@code pick} gets null for "All accounts".
     */
    public static void chooseProfile(
            final XmppActivity activity,
            final List<Account> accounts,
            @Nullable final Account current,
            final Consumer<Account> pick,
            final Runnable manage,
            final Runnable add) {
        final List<String> names = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        // the profile in use carries a tick, as in Telegram; the two actions below have none
        if (accounts.size() > 1) {
            names.add(mark(current == null) + activity.getString(R.string.all_accounts));
            actions.add(() -> pick.accept(null));
        }
        for (final Account account : accounts) {
            final boolean inUse =
                    current != null
                            ? current.getUuid().equals(account.getUuid())
                            : accounts.size() == 1;
            names.add(mark(inUse) + VeloNames.of(account));
            actions.add(() -> pick.accept(account));
        }
        names.add(activity.getString(R.string.action_accounts));
        actions.add(manage);
        names.add(activity.getString(R.string.action_add_account));
        actions.add(add);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_nav_choose_profile)
                .setItems(
                        names.toArray(new CharSequence[0]),
                        (dialog, which) -> actions.get(which).run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String mark(final boolean inUse) {
        return inUse ? "✓  " : "     ";
    }

    /** The profile picture keeps its own colours: the bar tints every other icon. */
    private static final class Untinted extends DrawableWrapper {

        Untinted(final Drawable drawable) {
            super(drawable);
        }

        @Override
        public void setTintList(@Nullable final ColorStateList tint) {}

        @Override
        public void setTintMode(@Nullable final PorterDuff.Mode tintMode) {}

        @Override
        public void setColorFilter(@Nullable final ColorFilter colorFilter) {}

        // without one, the bar uses this drawable as it is instead of a fresh, tintable copy
        @Nullable
        @Override
        public ConstantState getConstantState() {
            return null;
        }
    }

    /** True for the tabs that open their own screen instead of being selected. */
    public static boolean opensScreen(final int itemId) {
        return itemId == R.id.velo_nav_contacts
                || itemId == R.id.velo_nav_settings
                || itemId == R.id.velo_nav_profile;
    }

    /** The tab view, for the long press on Profile. */
    @Nullable
    public static View tab(final BottomNavigationView bar, final int itemId) {
        return bar == null ? null : bar.findViewById(itemId);
    }
}
