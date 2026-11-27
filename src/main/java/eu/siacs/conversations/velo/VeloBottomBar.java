package eu.siacs.conversations.velo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.EditAccountActivity;
import eu.siacs.conversations.ui.StartConversationActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.activity.SettingsActivity;
import eu.siacs.conversations.utils.AccountUtils;

/**
 * Velo 0.44: the bottom bar of the new Telegram - Chats, Contacts, Settings and Profile, the last
 * one with the profile's own picture. It replaces the side menu.
 *
 * <p>Velo 0.45: the same bar sits on all four sections (Contacts, Settings and Profile are their
 * own screens, which swap in place without animation, so Back always leads to Chats), the section
 * in use is the highlighted tab, and Personalizza → Barra chooses its size, shape, labels and
 * colours.
 */
public final class VeloBottomBar {

    public static final String SIZE = "velo_nav_size";
    public static final String SHAPE = "velo_nav_shape";
    public static final String LABELS = "velo_nav_labels";
    public static final String ACCENT = "velo_nav_accent";
    public static final String BACKGROUND = "velo_nav_background";
    public static final String SIZE_SMALL = "small", SIZE_NORMAL = "normal", SIZE_LARGE = "large";
    public static final String SHAPE_PILL = "pill", SHAPE_FULL = "full";
    public static final String LABELS_ALWAYS = "always", LABELS_SELECTED = "selected", LABELS_NEVER = "never";
    public static final String[] KEYS = {SIZE, SHAPE, LABELS, ACCENT, BACKGROUND};

    /** Extra of the Settings screen opened as a section of the bar. */
    public static final String EXTRA_TAB = "velo_tab";

    private static final ExecutorService AVATARS = Executors.newSingleThreadExecutor();
    private static final int TAG_PLACE = R.id.velo_nav_settings;

    /** The profile chosen in Chats (null: all of them), for the Profile tab of the other sections. */
    @Nullable private static String selectedAccountUuid;

    private VeloBottomBar() {}

    /**
     * Velo 0.44.1: up to 0.27 the Interface settings carried two hidden switches, and opening that
     * page was enough for Android to store their values then - bottom bar off, side menu on. A
     * stored value beats the new default, so on those phones 0.44.0 still showed the side menu.
     * Velo has no switch for either any more: the stored values go, once.
     */
    public static void forgetOldNavigationSettings(final SharedPreferences preferences) {
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

    private static SharedPreferences prefs(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static String size(final Context context) {
        return prefs(context).getString(SIZE, SIZE_NORMAL);
    }

    public static String shape(final Context context) {
        return prefs(context).getString(SHAPE, SHAPE_PILL);
    }

    public static String labels(final Context context) {
        return prefs(context).getString(LABELS, LABELS_ALWAYS);
    }

    /** The colour of the tab in use; the theme's buttons colour unless one was chosen. */
    public static int accent(final View view) {
        final int chosen = prefs(view.getContext()).getInt(ACCENT, 0);
        return chosen != 0 ? chosen : MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary);
    }

    /** The colour of the bar itself; the theme's page colour unless one was chosen. */
    public static int background(final View view) {
        final int chosen = prefs(view.getContext()).getInt(BACKGROUND, 0);
        return chosen != 0 ? chosen : MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface);
    }

    private static boolean pill(final Context context) {
        return !SHAPE_FULL.equals(shape(context));
    }

    /** Height of the bar's tabs, without what it adds to clear the system navigation bar. */
    private static int heightDp(final Context context) {
        final int base =
                switch (size(context)) {
                    case SIZE_SMALL -> 60;
                    case SIZE_LARGE -> 84;
                    default -> 72;
                };
        // without labels there is only the icon to hold
        return LABELS_NEVER.equals(labels(context)) ? base - 16 : base;
    }

    /**
     * Size, shape, colours and labels of the bar, from the choices in Personalizza. Safe to call
     * again, as on the way back from Personalizza: every value is set, nothing is added up.
     */
    public static void style(final BottomNavigationView bar) {
        if (bar == null) {
            return;
        }
        final Context context = bar.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final boolean pill = pill(context);
        final String size = size(context);
        final int accent = accent(bar);
        final int surface = background(bar);
        final int normal = VeloThemes.onColor(surface);
        final ColorStateList tint =
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {accent, normal});
        bar.setItemIconTintList(tint);
        bar.setItemTextColor(tint);
        bar.setItemActiveIndicatorEnabled(false);
        bar.setItemIconSize(Math.round((SIZE_SMALL.equals(size) ? 23 : SIZE_LARGE.equals(size) ? 30 : 27) * density));
        bar.setItemPaddingTop(Math.round((SIZE_SMALL.equals(size) ? 7 : SIZE_LARGE.equals(size) ? 12 : 9) * density));
        bar.setItemPaddingBottom(Math.round((SIZE_SMALL.equals(size) ? 4 : SIZE_LARGE.equals(size) ? 8 : 6) * density));
        bar.setActiveIndicatorLabelPadding(Math.round(3 * density));
        bar.setItemTextAppearanceActiveBoldEnabled(true);
        bar.setLabelVisibilityMode(
                switch (labels(context)) {
                    case LABELS_SELECTED -> BottomNavigationView.LABEL_VISIBILITY_SELECTED;
                    case LABELS_NEVER -> BottomNavigationView.LABEL_VISIBILITY_UNLABELED;
                    default -> BottomNavigationView.LABEL_VISIBILITY_LABELED;
                });
        final Drawable background;
        if (pill) {
            // Telegram's bar: a full pill on its shadow, with an edge to stand out on a page of the
            // same colour
            final GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(heightDp(context) / 2f * density);
            shape.setColor(surface);
            shape.setStroke(Math.max(1, Math.round(density)), ColorUtils.blendARGB(surface, normal, 0.14f));
            background = shape;
            bar.setElevation(8 * density);
        } else {
            // the classic bar: edge to edge, a hairline on top
            final LayerDrawable layers =
                    new LayerDrawable(
                            new Drawable[] {
                                new ColorDrawable(ColorUtils.blendARGB(surface, normal, 0.14f)),
                                new InsetDrawable(new ColorDrawable(surface), 0, Math.max(1, Math.round(density)), 0, 0)
                            });
            background = layers;
            bar.setElevation(0);
        }
        bar.setBackground(background);
        bar.setItemBackground(null);
        // setItemBackground just took every tab's pill away: paint them again, whatever was cached
        bar.setTag(R.id.chats, null);
        paintTabs(bar, accent);
        sizeLabels(bar, SIZE_SMALL.equals(size) ? 11 : SIZE_LARGE.equals(size) ? 15 : 13);
        place(bar);
    }

    /**
     * The labels' size, straight on the labels. Through setItemTextAppearance the bar measured them
     * a few pixels tall and cut them in half (0.44.0 and 0.45.0-prova, 19/11), so the size and a
     * line tall enough for the descenders are set on the two labels of every tab instead.
     */
    private static void sizeLabels(final BottomNavigationView bar, final int sp) {
        if (bar.getChildCount() == 0 || !(bar.getChildAt(0) instanceof ViewGroup)) {
            return;
        }
        final ViewGroup tabs = (ViewGroup) bar.getChildAt(0);
        final float scaled = bar.getResources().getDisplayMetrics().scaledDensity;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            for (final int id : new int[] {
                com.google.android.material.R.id.navigation_bar_item_small_label_view,
                com.google.android.material.R.id.navigation_bar_item_large_label_view
            }) {
                final View label = tabs.getChildAt(i).findViewById(id);
                if (label instanceof android.widget.TextView text) {
                    text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
                    androidx.core.widget.TextViewCompat.setLineHeight(text, Math.round(sp * 1.45f * scaled));
                    text.setIncludeFontPadding(true);
                }
            }
        }
    }

    /**
     * The pill under the tab in use, and the ripple of a touch. Every tab gets its own drawable:
     * one stateful drawable shared between tabs would show the state of whichever drew last.
     */
    private static void paintTabs(final BottomNavigationView bar, final int accent) {
        // the tabs can be measured taller than the bar shows them: the pill is fitted to the part
        // that shows, with a margin, once the bar is laid out (and again when that changes)
        if (bar.getTag(R.id.velo_nav_contacts) == null) {
            bar.setTag(R.id.velo_nav_contacts, Boolean.TRUE);
            bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paintTabs(bar, accent(bar)));
        }
        if (bar.getChildCount() == 0 || !(bar.getChildAt(0) instanceof ViewGroup)) {
            return;
        }
        final ViewGroup tabs = (ViewGroup) bar.getChildAt(0);
        final float density = bar.getResources().getDisplayMetrics().density;
        final int visible = bar.getHeight() - bar.getPaddingBottom();
        final int above = visible > 0 ? Math.max(0, -tabs.getTop()) : 0;
        final int below = visible > 0 ? Math.max(0, tabs.getBottom() - visible) : 0;
        final String key = accent + "|" + above + "|" + below + "|" + tabs.getChildCount();
        if (key.equals(bar.getTag(R.id.chats))) {
            return;
        }
        bar.setTag(R.id.chats, key);
        final int insetX = Math.round(3 * density);
        final int insetY = Math.round(5 * density);
        final int insetTop = insetY + above;
        final int insetBottom = insetY + below;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            final GradientDrawable selected = new GradientDrawable();
            selected.setCornerRadius(40 * density);
            selected.setColor(ColorUtils.setAlphaComponent(accent, 0x26));
            final StateListDrawable states = new StateListDrawable();
            states.addState(new int[] {android.R.attr.state_checked}, new InsetDrawable(selected, insetX, insetTop, insetX, insetBottom));
            states.addState(new int[] {}, new ColorDrawable(0));
            final GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(40 * density);
            mask.setColor(0xFFFFFFFF);
            final View tab = tabs.getChildAt(i);
            tab.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x1F)),
                            states,
                            new InsetDrawable(mask, insetX, insetTop, insetX, insetBottom)));
            // an inset background hands its insets to the tab as padding: the tab then asked for
            // more height than the bar has and the labels were squeezed to half (0.45.0-prova)
            tab.setPadding(0, 0, 0, 0);
            // the tabs too come with an 80dp minimum, taller than a normal or small bar
            tab.setMinimumHeight(0);
        }
    }

    /**
     * Holds the bar clear of the system navigation bar, from the insets the bar itself is handed:
     * a screen whose window already keeps its content out of there hands it none. The pill floats
     * a little above; the full bar runs under the navigation bar, so its colour reaches the edge.
     */
    private static void place(final BottomNavigationView bar) {
        final Context context = bar.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final boolean pill = pill(context);
        final int height = Math.round(heightDp(context) * density);
        final int side = pill ? Math.round(12 * density) : 0;
        final int lift = pill ? Math.round(10 * density) : 0;
        // Material's style gives the bar a minimum of 80dp: its tabs were measured that tall inside
        // a lower bar and the pill of the chosen tab stuck out above it
        bar.setMinimumHeight(0);
        final Runnable[] apply = new Runnable[1];
        final int[] navigation = {0};
        apply[0] =
                () -> {
                    final ViewGroup.LayoutParams params = bar.getLayoutParams();
                    if (!(params instanceof ViewGroup.MarginLayoutParams)) {
                        return;
                    }
                    final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    final int wantHeight = pill ? height : height + navigation[0];
                    final int wantBottom = pill ? lift + navigation[0] : 0;
                    final int wantPadding = pill ? 0 : navigation[0];
                    if (margins.height != wantHeight
                            || margins.bottomMargin != wantBottom
                            || margins.leftMargin != side
                            || margins.rightMargin != side
                            || bar.getPaddingBottom() != wantPadding) {
                        margins.height = wantHeight;
                        margins.bottomMargin = wantBottom;
                        margins.leftMargin = side;
                        margins.rightMargin = side;
                        margins.setMarginStart(side);
                        margins.setMarginEnd(side);
                        bar.setPadding(0, 0, 0, wantPadding);
                        bar.setLayoutParams(margins);
                    }
                    fade(bar, pill ? navigation[0] + lift + height + Math.round(18 * density) : 0);
                };
        bar.setTag(TAG_PLACE, apply[0]);
        ViewCompat.setOnApplyWindowInsetsListener(
                bar,
                (v, insets) -> {
                    navigation[0] = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                    apply[0].run();
                    return insets;
                });
        apply[0].run();
        ViewCompat.requestApplyInsets(bar);
    }

    private static final int TAG_FADE = R.id.velo_tag_fade;

    /**
     * Velo 0.46.6: under the floating pill of the chat list the rows ran on behind the system
     * buttons, half cut, a mess of text. As in Telegram, a fade in the page colour now sits behind
     * the pill and the system buttons. Only where the list scrolls under the bar (the chat list's
     * RelativeLayout): the other sections are held clear of the bar already. {@code height} 0
     * (the full bar, which reaches the edge itself) takes the fade away.
     */
    private static void fade(final BottomNavigationView bar, final int height) {
        if (!(bar.getParent() instanceof android.widget.RelativeLayout parent)) {
            return;
        }
        View scrim = (View) bar.getTag(TAG_FADE);
        if (scrim == null) {
            if (height == 0) {
                return;
            }
            scrim = new View(bar.getContext());
            scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final android.widget.RelativeLayout.LayoutParams params =
                    new android.widget.RelativeLayout.LayoutParams(-1, height);
            params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            // right under the bar, so it covers the list but never the bar
            parent.addView(scrim, parent.indexOfChild(bar), params);
            bar.setTag(TAG_FADE, scrim);
            final View fadeView = scrim;
            bar.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                final int want = bar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE;
                if (fadeView.getVisibility() != want && fadeView.getLayoutParams().height > 0) {
                    fadeView.setVisibility(want);
                }
            });
        }
        final int page = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorSurface);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {
                page, ColorUtils.setAlphaComponent(page, 0xEB), ColorUtils.setAlphaComponent(page, 0)}));
        final ViewGroup.LayoutParams params = scrim.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            scrim.setLayoutParams(params);
        }
        scrim.setVisibility(height > 0 && bar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    /**
     * Puts the bar on a section that is a screen of its own (Contacts, Settings, Profile), with
     * {@code selected} as the tab in use. The bar floats over the bottom of the screen and the
     * screen is held clear of it, so the last line of a list never ends up underneath.
     */
    public static BottomNavigationView attach(final XmppActivity activity, final int selected) {
        final ViewGroup content = activity.findViewById(android.R.id.content);
        final BottomNavigationView bar = new BottomNavigationView(activity);
        bar.setId(R.id.bottom_navigation);
        bar.inflateMenu(R.menu.bottom_navigation_menu_chat);
        bar.setSelectedItemId(selected);
        content.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        style(bar);
        final View screen = content.getChildAt(0);
        bar.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    if (screen == bar || !(screen.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                        return;
                    }
                    final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) screen.getLayoutParams();
                    final ViewGroup.MarginLayoutParams barParams = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
                    final int below = bar.getVisibility() == View.GONE ? 0 : bar.getHeight() + barParams.bottomMargin;
                    if (params.bottomMargin != below) {
                        params.bottomMargin = below;
                        screen.post(() -> screen.setLayoutParams(params));
                    }
                });
        bar.setOnItemSelectedListener(
                item -> {
                    final int id = item.getItemId();
                    if (id != selected) {
                        open(activity, id);
                    }
                    return id == selected;
                });
        bar.setOnItemReselectedListener(item -> {});
        return bar;
    }

    /** Badge and picture of a bar put on with {@link #attach}, once the service is there. */
    public static void refresh(final XmppActivity activity) {
        final View view = activity.findViewById(R.id.bottom_navigation);
        if (!(view instanceof BottomNavigationView) || activity.xmppConnectionService == null) {
            return;
        }
        final BottomNavigationView bar = (BottomNavigationView) view;
        final int unread = activity.xmppConnectionService.unreadCount();
        final var badge = bar.getOrCreateBadge(R.id.chats);
        badge.setNumber(unread);
        badge.setVisible(unread > 0);
        showAvatar(activity, bar, profileAccount(activity));
    }

    /**
     * Goes to the section {@code id} from {@code from}, as Telegram swaps its tabs: in place and
     * without animation. Only Chats stays underneath, so Back from any section leads there.
     */
    public static void open(final XmppActivity from, final int id) {
        final boolean fromChats = from instanceof ConversationsActivity;
        if (id == R.id.chats) {
            if (!fromChats) {
                from.startActivity(
                        new Intent(from, ConversationsActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                leave(from);
            }
        } else if (id == R.id.velo_nav_contacts) {
            StartConversationActivity.browse(from, selectedAccount(from));
            leave(from);
        } else if (id == R.id.velo_nav_settings) {
            from.startActivity(new Intent(from, SettingsActivity.class).putExtra(EXTRA_TAB, true));
            leave(from);
        } else if (id == R.id.velo_nav_profile) {
            final Account account = profileAccount(from);
            if (account == null) {
                return;
            }
            final boolean all =
                    selectedAccountUuid == null
                            && from.xmppConnectionService != null
                            && from.xmppConnectionService.getAccounts().size() > 1;
            if (all && fromChats) {
                // "All accounts" in Chats: which profile is "mine" is a question, as in the old menu
                chooseProfile(
                        from,
                        from.xmppConnectionService.getAccounts(),
                        null,
                        chosen -> {
                            if (chosen != null) {
                                ((ConversationsActivity) from).veloSelectProfile(chosen);
                                VeloProfileActivity.open(from, chosen);
                                from.overridePendingTransition(0, 0);
                            }
                        },
                        () -> AccountUtils.launchManageAccounts(from),
                        () -> from.startActivity(new Intent(from, EditAccountActivity.class)));
                return;
            }
            VeloProfileActivity.open(from, account);
            leave(from);
        }
    }

    private static void leave(final Activity from) {
        from.overridePendingTransition(0, 0);
        if (!(from instanceof ConversationsActivity)) {
            from.finish();
            from.overridePendingTransition(0, 0);
        }
    }

    /** Chats tells the bar which profile is chosen, for the sections that are screens of their own. */
    public static void rememberSelected(@Nullable final Account account) {
        selectedAccountUuid = account == null ? null : account.getUuid();
    }

    private static boolean choiceForChats = false;

    /** A profile chosen on the Profile page: Chats switches to it when it comes back. */
    public static void chooseForChats(@Nullable final Account account) {
        rememberSelected(account);
        choiceForChats = true;
    }

    /** Velo 0.54: what {@link #takeChoiceForChats} answers when "All accounts" was chosen. */
    public static final String ALL_PROFILES = "*all*";

    /** The profile chosen elsewhere that Chats still has to switch to, if any (once). */
    @Nullable
    public static String takeChoiceForChats() {
        if (!choiceForChats) {
            return null;
        }
        choiceForChats = false;
        // Velo 0.54: "All accounts" (null) was mistaken for "no choice", so Chats kept one profile
        return selectedAccountUuid == null ? ALL_PROFILES : selectedAccountUuid;
    }

    /** Shows or hides a bar put on with {@link #attach}, and the room kept for it under the screen. */
    public static void setShown(final Activity activity, final boolean shown) {
        final View bar = activity.findViewById(R.id.bottom_navigation);
        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (bar == null || content == null || content.getChildCount() == 0) {
            return;
        }
        bar.setVisibility(shown ? View.VISIBLE : View.GONE);
        final View screen = content.getChildAt(0);
        if (!shown && screen.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) screen.getLayoutParams();
            params.bottomMargin = 0;
            screen.setLayoutParams(params);
        }
    }

    @Nullable
    private static Account selectedAccount(final XmppActivity activity) {
        if (selectedAccountUuid == null || activity.xmppConnectionService == null) {
            return null;
        }
        for (final Account account : activity.xmppConnectionService.getAccounts()) {
            if (selectedAccountUuid.equals(account.getUuid())) {
                return account;
            }
        }
        return null;
    }

    /** The profile the Profile tab stands for: the chosen one, or the first. */
    @Nullable
    public static Account profileAccount(final XmppActivity activity) {
        final Account selected = selectedAccount(activity);
        if (selected != null || activity.xmppConnectionService == null) {
            return selected;
        }
        final List<Account> accounts = activity.xmppConnectionService.getAccounts();
        return accounts.isEmpty() ? null : accounts.get(0);
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
        final int size = Math.round(30 * activity.getResources().getDisplayMetrics().density);
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
        final List<ProfileRow> rows = new ArrayList<>();
        // the profile in use carries a tick, as in Telegram; the two actions below have none
        if (accounts.size() > 1) {
            rows.add(new ProfileRow(null, R.drawable.ic_group_24dp,
                    activity.getString(R.string.all_accounts), null, current == null, () -> pick.accept(null)));
        }
        for (final Account account : accounts) {
            final boolean inUse =
                    current != null
                            ? current.getUuid().equals(account.getUuid())
                            : accounts.size() == 1;
            final String local = account.getJid().getLocal();
            rows.add(new ProfileRow(account, 0, VeloNames.of(account),
                    local == null ? null : "@" + local, inUse, () -> pick.accept(account)));
        }
        rows.add(new ProfileRow(null, R.drawable.ic_settings_24dp,
                activity.getString(R.string.action_accounts), null, false, manage));
        rows.add(new ProfileRow(null, R.drawable.ic_add_24dp,
                activity.getString(R.string.action_add_account), null, false, add));
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_nav_choose_profile)
                .setAdapter(new ProfileAdapter(activity, rows), (dialog, which) -> rows.get(which).action.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** One line of the profile chooser: a profile with its picture, or an action with an icon. */
    private static final class ProfileRow {
        @Nullable final Account account;
        final int icon;
        final String title;
        @Nullable final String subtitle;
        final boolean inUse;
        final Runnable action;

        ProfileRow(@Nullable final Account account, final int icon, final String title,
                @Nullable final String subtitle, final boolean inUse, final Runnable action) {
            this.account = account;
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.inUse = inUse;
            this.action = action;
        }
    }

    /**
     * Velo 0.47: the chooser as Telegram's - each profile with its own picture and "@user" under
     * the name, a tick on the one in use - instead of a plain list of names.
     */
    private static final class ProfileAdapter extends android.widget.BaseAdapter {

        private final XmppActivity activity;
        private final List<ProfileRow> rows;

        ProfileAdapter(final XmppActivity activity, final List<ProfileRow> rows) {
            this.activity = activity;
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(final int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(final int position) {
            return position;
        }

        private int dp(final int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        @Override
        public View getView(final int position, @Nullable final View convertView, final ViewGroup parent) {
            final ProfileRow row = rows.get(position);
            final int surface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceContainerHigh);
            final int text = VeloThemes.onColor(surface);
            final int primary = MaterialColors.getColor(parent, androidx.appcompat.R.attr.colorPrimary);
            final android.widget.LinearLayout line = new android.widget.LinearLayout(activity);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(64));
            line.setPadding(dp(24), dp(8), dp(20), dp(8));

            final com.google.android.material.imageview.ShapeableImageView picture =
                    new com.google.android.material.imageview.ShapeableImageView(activity);
            picture.setShapeAppearanceModel(com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(new com.google.android.material.shape.RelativeCornerSize(0.5f)).build());
            picture.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            if (row.account != null) {
                eu.siacs.conversations.ui.util.AvatarWorkerTask.loadAvatar(row.account, picture, R.dimen.avatar);
            } else {
                // the actions get their icon on a soft disc the size of a picture, so all line up
                picture.setImageResource(row.icon);
                picture.setColorFilter(primary);
                picture.setBackgroundColor(ColorUtils.setAlphaComponent(primary, 0x22));
                picture.setPadding(dp(10), dp(10), dp(10), dp(10));
            }
            line.addView(picture, new android.widget.LinearLayout.LayoutParams(dp(44), dp(44)));

            final android.widget.LinearLayout texts = new android.widget.LinearLayout(activity);
            texts.setOrientation(android.widget.LinearLayout.VERTICAL);
            texts.setPadding(dp(16), 0, dp(8), 0);
            final android.widget.TextView title = new android.widget.TextView(activity);
            title.setText(row.title);
            title.setTextSize(16);
            title.setTextColor(text);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (row.inUse) {
                title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            }
            texts.addView(title);
            if (row.subtitle != null && !row.subtitle.equals("@" + row.title)) {
                final android.widget.TextView subtitle = new android.widget.TextView(activity);
                subtitle.setText(row.subtitle);
                subtitle.setTextSize(13);
                subtitle.setTextColor(ColorUtils.blendARGB(text, surface, 0.4f));
                subtitle.setSingleLine(true);
                texts.addView(subtitle);
            }
            line.addView(texts, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

            if (row.inUse) {
                final android.widget.ImageView tick = new android.widget.ImageView(activity);
                tick.setImageResource(R.drawable.ic_check_24dp);
                tick.setColorFilter(primary);
                tick.setContentDescription(activity.getString(R.string.velo_profile_in_use));
                line.addView(tick, new android.widget.LinearLayout.LayoutParams(dp(24), dp(24)));
            }
            return line;
        }
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

    /** The tab view, for the long press on Profile. */
    @Nullable
    public static View tab(final BottomNavigationView bar, final int itemId) {
        return bar == null ? null : bar.findViewById(itemId);
    }
}
