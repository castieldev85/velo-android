package eu.siacs.conversations.velo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.RelativeCornerSize;
import com.google.android.material.shape.ShapeAppearanceModel;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.ChangePasswordActivity;
import eu.siacs.conversations.ui.EditAccountActivity;
import eu.siacs.conversations.ui.PublishProfilePictureActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.AccountUtils;

/**
 * Velo 0.45: the Profile section of the bottom bar, as Telegram's: the picture large on the bar
 * colour, the name, the user name - never the server - and the things that belong to oneself
 * below it. The technical account page stays one tap away ("Devices and keys").
 */
public class VeloProfileActivity extends XmppActivity {

    private LinearLayout root;
    private LinearLayout page;
    @Nullable private Account account;
    @Nullable private String jid;

    public static void open(final XmppActivity from, final Account account) {
        from.startActivity(
                new Intent(from, VeloProfileActivity.class)
                        .putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString()));
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        jid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        final ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        Activities.setStatusAndNavigationBarColors(this, root);
        VeloBottomBar.attach(this, R.id.velo_nav_profile);
    }

    @Override
    protected void onBackendConnected() {
        account = null;
        for (final Account candidate : xmppConnectionService.getAccounts()) {
            if (candidate.getJid().asBareJid().toString().equals(jid)) {
                account = candidate;
            }
        }
        if (account == null) {
            account = VeloBottomBar.profileAccount(this);
        }
        render();
        VeloBottomBar.refresh(this);
    }

    @Override
    protected void refreshUiReal() {
        if (xmppConnectionService != null) {
            render();
            VeloBottomBar.refresh(this);
        }
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void render() {
        page.removeAllViews();
        final int surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface);
        root.setBackgroundColor(surface);
        if (account == null) {
            return;
        }
        page.addView(header());
        final int card = ColorUtils.blendARGB(surface, VeloThemes.onColor(surface), 0.05f);
        final String name = account.getDisplayName();
        final String user = account.getJid().getLocal() == null ? "" : account.getJid().getLocal();
        group(card,
                row(R.drawable.ic_person_24dp, TextUtils.isEmpty(name) ? getString(R.string.velo_profile_name) : name,
                        getString(TextUtils.isEmpty(name) ? R.string.velo_profile_name_hint : R.string.velo_profile_name),
                        v -> editName()),
                row(R.drawable.ic_person_24dp, "@" + user, getString(R.string.velo_profile_username), v -> copy(user)),
                row(R.drawable.ic_image_24dp, getString(R.string.velo_profile_photo), getString(R.string.velo_profile_photo_hint),
                        v -> changePhoto()));
        group(card,
                row(R.drawable.ic_qr_code_24dp, getString(R.string.velo_profile_invite),
                        getString(R.string.velo_profile_invite_hint), v -> VeloFriendInviteUi.start(this)),
                row(R.drawable.ic_edit_24dp, getString(R.string.velo_profile_notes), null, v -> notes()),
                row(R.drawable.ic_lock_24dp, getString(R.string.velo_profile_password), null, v -> changePassword()));
        group(card,
                row(R.drawable.ic_settings_24dp, getString(R.string.velo_profile_devices),
                        getString(R.string.velo_profile_devices_hint), v -> switchToAccount(account)),
                row(R.drawable.rounded_account_circle_24, getString(R.string.velo_profile_profiles),
                        getString(R.string.velo_profile_profiles_hint), v -> profiles()));
        final View space = new View(this);
        page.addView(space, new LinearLayout.LayoutParams(-1, dp(24)));
    }

    /** Telegram's top: the picture large and round, the name, then "@user · online". */
    private View header() {
        final int bar = VeloUi.barColor(this);
        final int on = VeloThemes.onColor(bar);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundColor(bar);
        box.setPadding(dp(16), dp(28), dp(16), dp(22));
        final ShapeableImageView avatar = new ShapeableImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setShapeAppearanceModel(
                ShapeAppearanceModel.builder().setAllCornerSizes(new RelativeCornerSize(0.5f)).build());
        avatar.setContentDescription(getString(R.string.velo_profile_photo));
        avatar.setOnClickListener(v -> changePhoto());
        AvatarWorkerTask.loadAvatar(account, avatar, R.dimen.avatar_on_details_screen_size);
        box.addView(avatar, new LinearLayout.LayoutParams(dp(104), dp(104)));
        final TextView name = new TextView(this);
        name.setText(VeloNames.of(account));
        name.setTextColor(on);
        name.setTextSize(24);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(14), 0, 0);
        box.addView(name);
        final TextView status = new TextView(this);
        final String user = account.getJid().getLocal() == null ? "" : "@" + account.getJid().getLocal();
        status.setText(user + " · " + getString(account.isOnlineAndConnected()
                ? R.string.velo_profile_online : R.string.velo_profile_offline));
        status.setTextColor(ColorUtils.setAlphaComponent(on, 0xCC));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, 0);
        box.addView(status);
        return box;
    }

    private void group(final int colour, final View... rows) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable background = new GradientDrawable();
        background.setColor(colour);
        background.setCornerRadius(dp(22));
        box.setBackground(background);
        box.setClipToOutline(true);
        for (final View row : rows) {
            box.addView(row);
        }
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(14), dp(14), dp(14), 0);
        page.addView(box, params);
    }

    private View row(@DrawableRes final int icon, final String title, @Nullable final String subtitle, final View.OnClickListener click) {
        final int surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface);
        final int text = VeloThemes.onColor(surface);
        final int primary = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary);
        final LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(60));
        line.setPadding(dp(16), dp(10), dp(16), dp(10));
        final android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        line.setBackgroundResource(ripple.resourceId);
        line.setOnClickListener(click);
        final ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setColorFilter(primary);
        line.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));
        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(18), 0, 0, 0);
        final TextView first = new TextView(this);
        first.setText(title);
        first.setTextSize(16);
        first.setTextColor(text);
        texts.addView(first);
        if (subtitle != null) {
            final TextView second = new TextView(this);
            second.setText(subtitle);
            second.setTextSize(13);
            second.setTextColor(ColorUtils.blendARGB(text, surface, 0.35f));
            texts.addView(second);
        }
        line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return line;
    }

    private void editName() {
        if (account == null) {
            return;
        }
        quickEdit(
                account.getDisplayName(),
                R.string.your_name,
                value -> {
                    account.setDisplayName(value.trim());
                    xmppConnectionService.publishDisplayName(account);
                    render();
                    return null;
                },
                true);
    }

    private void copy(final String user) {
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.velo_profile_username), user));
            Toast.makeText(this, R.string.velo_profile_username_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void changePhoto() {
        if (account != null) {
            startActivity(new Intent(this, PublishProfilePictureActivity.class)
                    .putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString()));
        }
    }

    private void notes() {
        if (account == null) {
            return;
        }
        final Conversation conversation = xmppConnectionService.findOrCreateConversation(
                account, account.getSelfContact().getJid(), false, false, null, true, null);
        switchToConversation(conversation);
    }

    /** Velo's own strong-password page for the profile it looks after, monocles' for the others. */
    private void changePassword() {
        if (account == null) {
            return;
        }
        final Account main = VeloUpdater.account(xmppConnectionService);
        if (main != null && main.getUuid().equals(account.getUuid())) {
            startActivity(new Intent(this, VeloPasswordActivity.class));
        } else {
            startActivity(new Intent(this, ChangePasswordActivity.class)
                    .putExtra(EXTRA_ACCOUNT, account.getJid().toString()));
        }
    }

    private void profiles() {
        VeloBottomBar.chooseProfile(
                this,
                xmppConnectionService.getAccounts(),
                account,
                chosen -> {
                    if (chosen != null) {
                        VeloBottomBar.chooseForChats(chosen);
                        account = chosen;
                        jid = chosen.getJid().asBareJid().toString();
                        render();
                        VeloBottomBar.refresh(this);
                    }
                },
                () -> AccountUtils.launchManageAccounts(this),
                () -> startActivity(new Intent(this, EditAccountActivity.class)));
    }
}
