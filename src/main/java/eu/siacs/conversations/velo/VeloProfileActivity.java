package eu.siacs.conversations.velo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
public class VeloProfileActivity extends XmppActivity
        implements eu.siacs.conversations.services.XmppConnectionService.OnAccountUpdate {

    /**
     * Velo 0.47: the page follows the profile's connection, so "connecting…" turns into "online"
     * and the cover is asked for as soon as the profile is online.
     */
    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    private LinearLayout root;
    private LinearLayout page;
    @Nullable private Account account;
    @Nullable private String jid;
    /** The cover shown in the header, kept so that a render does not flash the plain band. */
    @Nullable private Bitmap cover;
    @Nullable private String coverOf;
    @Nullable private String coverFetchedFor;

    // Velo 0.47: the cover is picked from the gallery, then cut to 3:1 by the same cropper as
    // the profile picture
    private final ActivityResultLauncher<CropImageContractOptions> cropCover =
            registerForActivityResult(new CropImageContract(), result -> {
                if (result.isSuccessful() && result.getUriContent() != null) {
                    publishCover(result.getUriContent());
                }
            });
    private final ActivityResultLauncher<String> pickCover =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) {
                    return;
                }
                final CropImageOptions options = new CropImageOptions();
                options.aspectRatioX = 3;
                options.aspectRatioY = 1;
                options.fixAspectRatio = true;
                options.outputCompressFormat = Bitmap.CompressFormat.JPEG;
                options.outputCompressQuality = 90;
                options.outputRequestWidth = VeloCover.WIDTH;
                options.outputRequestHeight = VeloCover.HEIGHT;
                options.outputRequestSizeOptions = CropImageView.RequestSizeOptions.RESIZE_INSIDE;
                options.activityTitle = getString(R.string.velo_cover);
                cropCover.launch(new CropImageContractOptions(uri, options));
            });

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
        fetchCover();
    }

    @Override
    protected void refreshUiReal() {
        if (xmppConnectionService != null) {
            render();
            VeloBottomBar.refresh(this);
            fetchCover();
        }
    }

    /**
     * Asks the server for the cover once per profile shown, as soon as that profile is online:
     * opened while still connecting, the page asks when the connection comes up.
     */
    private void fetchCover() {
        final Account shown = account;
        if (shown == null || shown.getStatus() != Account.State.ONLINE) {
            return;
        }
        final String key = shown.getJid().asBareJid().toString();
        if (key.equals(coverFetchedFor)) {
            return;
        }
        coverFetchedFor = key;
        VeloCover.fetch(xmppConnectionService, shown, shown.getJid(), bitmap -> runOnUiThread(() -> {
            if (shown == account) {
                cover = bitmap;
                render();
            }
        }));
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

    /**
     * Telegram's top: the cover across the top (the bar colour while there is none), the picture
     * large and round on its lower edge, the name, then "@user · online".
     */
    private View header() {
        final int bar = VeloUi.barColor(this);
        final int surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface);
        final int text = VeloThemes.onColor(surface);
        final String key = account.getJid().asBareJid().toString();
        if (!key.equals(coverOf)) {
            coverOf = key;
            cover = VeloCover.cached(this, account.getJid());
        }
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, 0, 0, dp(18));

        final FrameLayout band = new FrameLayout(this);
        band.setBackgroundColor(bar);
        band.setContentDescription(getString(R.string.velo_cover));
        band.setOnClickListener(v -> coverMenu());
        if (cover != null) {
            final ImageView picture = new ImageView(this);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setImageBitmap(cover);
            band.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        }
        band.addView(coverChip(cover == null), chipParams());
        final int width = getResources().getDisplayMetrics().widthPixels;
        box.addView(band, new LinearLayout.LayoutParams(-1, width / 3));

        final ShapeableImageView avatar = new ShapeableImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setShapeAppearanceModel(
                ShapeAppearanceModel.builder().setAllCornerSizes(new RelativeCornerSize(0.5f)).build());
        // a ring in the page colour parts the picture from the cover behind it
        avatar.setStrokeColor(android.content.res.ColorStateList.valueOf(surface));
        avatar.setStrokeWidth(dp(4));
        avatar.setPadding(dp(2), dp(2), dp(2), dp(2));
        avatar.setBackgroundColor(surface);
        avatar.setContentDescription(getString(R.string.velo_profile_photo));
        avatar.setOnClickListener(v -> changePhoto());
        // Velo 0.50: holding the own picture shows it big
        avatar.setOnLongClickListener(v -> {
            if (account != null) {
                ShowAvatarPopup(account);
            }
            return true;
        });
        AvatarWorkerTask.loadAvatar(account, avatar, R.dimen.avatar_on_details_screen_size);
        final LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(108), dp(108));
        avatarParams.topMargin = -dp(54);
        box.addView(avatar, avatarParams);

        final TextView name = new TextView(this);
        name.setText(VeloNames.of(account));
        name.setTextColor(text);
        name.setTextSize(24);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(16), dp(10), dp(16), 0);
        box.addView(name);
        final TextView status = new TextView(this);
        final String user = account.getJid().getLocal() == null ? "" : "@" + account.getJid().getLocal();
        status.setText(user + " · " + getString(account.isOnlineAndConnected()
                ? R.string.velo_profile_online : R.string.velo_profile_offline));
        status.setTextColor(ColorUtils.blendARGB(text, surface, 0.35f));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, 0);
        box.addView(status);
        return box;
    }

    /** The round dark button on the cover: a camera, plus "Add cover" while there is none. */
    private View coverChip(final boolean withText) {
        final TextView chip = new TextView(this);
        chip.setTextColor(0xFFFFFFFF);
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setCompoundDrawablePadding(withText ? dp(8) : 0);
        final android.graphics.drawable.Drawable camera =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_camera_alt_24dp);
        if (camera != null) {
            final android.graphics.drawable.Drawable icon = camera.mutate();
            icon.setTint(0xFFFFFFFF);
            icon.setBounds(0, 0, dp(20), dp(20));
            chip.setCompoundDrawables(icon, null, null, null);
        }
        if (withText) {
            chip.setText(R.string.velo_cover_add);
            chip.setPadding(dp(12), dp(7), dp(14), dp(7));
        } else {
            chip.setPadding(dp(8), dp(8), dp(8), dp(8));
            chip.setContentDescription(getString(R.string.velo_cover_change));
        }
        final GradientDrawable background = new GradientDrawable();
        background.setColor(0x78000000);
        background.setCornerRadius(dp(20));
        chip.setBackground(background);
        chip.setOnClickListener(v -> coverMenu());
        return chip;
    }

    private FrameLayout.LayoutParams chipParams() {
        final FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(12), dp(12), 0);
        return params;
    }

    private void coverMenu() {
        if (account == null) {
            return;
        }
        if (cover == null) {
            pickCover.launch("image/*");
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.velo_cover)
                .setItems(
                        new CharSequence[] {getString(R.string.velo_cover_change), getString(R.string.velo_cover_remove)},
                        (dialog, which) -> {
                            if (which == 0) {
                                pickCover.launch("image/*");
                            } else {
                                VeloCover.remove(xmppConnectionService, account);
                                cover = null;
                                render();
                                Toast.makeText(this, R.string.velo_cover_removed, Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void publishCover(final android.net.Uri uri) {
        final Account target = account;
        if (target == null || xmppConnectionService == null) {
            return;
        }
        if (!target.isOnlineAndConnected()) {
            Toast.makeText(this, R.string.velo_cover_offline, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.velo_cover_sending, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final byte[] jpeg = VeloCover.encode(this, uri);
            if (jpeg == null) {
                runOnUiThread(() -> Toast.makeText(this, R.string.velo_cover_failed, Toast.LENGTH_LONG).show());
                return;
            }
            VeloCover.publish(xmppConnectionService, target, jpeg, ok -> runOnUiThread(() -> {
                if (ok) {
                    if (target == account) {
                        cover = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        render();
                    }
                    Toast.makeText(this, R.string.velo_cover_updated, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.velo_cover_failed, Toast.LENGTH_LONG).show();
                }
            }));
        }).start();
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
                    if (chosen == null) {
                        // Velo 0.54: this page shows one profile at a time; "All accounts" is for
                        // the chat list, so back to it
                        VeloBottomBar.chooseForChats(null);
                        VeloBottomBar.open(this, R.id.chats);
                    } else {
                        VeloBottomBar.chooseForChats(chosen);
                        account = chosen;
                        jid = chosen.getJid().asBareJid().toString();
                        render();
                        VeloBottomBar.refresh(this);
                        fetchCover();
                    }
                },
                () -> AccountUtils.launchManageAccounts(this),
                () -> startActivity(new Intent(this, EditAccountActivity.class)));
    }
}
