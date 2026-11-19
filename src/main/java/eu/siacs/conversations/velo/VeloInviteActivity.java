package eu.siacs.conversations.velo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.InputStream;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.BaseActivity;
import eu.siacs.conversations.ui.EditAccountActivity;
import eu.siacs.conversations.ui.ScanActivity;

/**
 * Velo 0.39: "I have an invite". Reads the invite QR code (or a {@code velo:invite?...} link
 * opened with VIEW), shows the group's name and its verification code, and only after the user
 * confirms opens the login page for that server: user name and temporary password, nothing else.
 *
 * <p>Velo 0.39.3: the invite can also come from a photo of the QR code (a screenshot, when the
 * group was created on this same phone) or from the clipboard. The verification code check is
 * the same in every case.
 */
public class VeloInviteActivity extends BaseActivity {

    private static final int REQUEST_PICK_IMAGE = 0x7a11;
    private static final int MAX_SIDE = 2048;

    /** The login page for an invited server: with a token it creates the account. */
    static Intent joinIntent(
            final Context context,
            final VeloServers.Server server,
            @Nullable final String token,
            @Nullable final String inviter,
            @Nullable final String user) {
        final Intent intent = new Intent(context, EditAccountActivity.class);
        intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, token != null);
        if (token != null) {
            intent.putExtra(VeloFriendInvite.EXTRA_TOKEN, token);
            intent.putExtra(VeloFriendInvite.EXTRA_INVITER, inviter);
        }
        if (user != null) {
            intent.putExtra(VeloFriendInvite.EXTRA_USER, user);
        }
        intent.putExtra("init", true);
        intent.putExtra(VeloServers.EXTRA_INVITE, server.toInvite());
        return intent;
    }

    private boolean busy;

    public static void start(final Activity activity) {
        activity.startActivity(new Intent(activity, VeloInviteActivity.class));
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            busy = savedInstanceState.getBoolean("busy", false);
            if (busy) {
                return; // the scanner or the picker is already open and will answer in onActivityResult
            }
        }
        final Uri data = getIntent().getData();
        if (data != null) {
            handle(VeloServers.findInvite(data.toString()));
        } else {
            chooseSource();
        }
    }

    private void chooseSource() {
        final CharSequence[] choices = {
            getString(R.string.velo_invite_from_camera),
            getString(R.string.velo_invite_from_photo),
            getString(R.string.velo_invite_from_paste)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.velo_invite_have)
                .setItems(
                        choices,
                        (d, which) -> {
                            if (which == 0) {
                                busy = true;
                                ScanActivity.scan(this);
                            } else if (which == 1) {
                                busy = true;
                                final Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                                pick.setType("image/*");
                                pick.addCategory(Intent.CATEGORY_OPENABLE);
                                try {
                                    startActivityForResult(pick, REQUEST_PICK_IMAGE);
                                } catch (final android.content.ActivityNotFoundException e) {
                                    busy = false;
                                    handle(null);
                                }
                            } else {
                                handle(fromClipboard());
                            }
                        })
                .setOnCancelListener(d -> finish())
                .show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("busy", busy);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, @Nullable final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ScanActivity.REQUEST_SCAN_QR_CODE) {
            busy = false;
            if (resultCode != RESULT_OK || data == null) {
                finish();
                return;
            }
            handle(data.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT));
        } else if (requestCode == REQUEST_PICK_IMAGE) {
            busy = false;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                finish();
                return;
            }
            final Uri image = data.getData();
            new Thread(
                            () -> {
                                final String text = decodeQr(image);
                                runOnUiThread(
                                        () -> {
                                            if (!isFinishing() && !isDestroyed()) {
                                                handle(text);
                                            }
                                        });
                            },
                            "velo-invite-photo")
                    .start();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        ScanActivity.onRequestPermissionResult(this, requestCode, grantResults);
        if (grantResults.length == 0 || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            finish();
        }
    }

    @Nullable
    private String fromClipboard() {
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return null;
        }
        final ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        final CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : VeloServers.findInvite(text.toString());
    }

    /** Reads a QR code from a picture, entirely on the phone. Returns null if there is none. */
    @Nullable
    private String decodeQr(final Uri image) {
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(image)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) {
                sample *= 2;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            final Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(image)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                return null;
            }
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            final int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            return VeloServers.decodeQr(width, height, pixels);
        } catch (final Exception | OutOfMemoryError e) {
            Log.d("velo", "invite: no QR code in the picture: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private void handle(@Nullable final String scanned) {
        // A QR code or a paste may hold the site link instead of the velo:invite text
        final String text = VeloServers.findInvite(scanned);
        // Velo 0.42: an invite from "Invite a friend" also carries the token to create the account
        final String token = VeloFriendInvite.token(text);
        final String inviter = VeloFriendInvite.inviter(text);
        // Velo 0.43.1: also with a token, the personal invite of a trial group member
        final String user = VeloFriendInvite.user(text);
        final VeloServers.Server server;
        try {
            server = VeloServers.parseInvite(text);
        } catch (final IllegalArgumentException e) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.velo_invite_invalid_title)
                    .setMessage(R.string.velo_invite_invalid_message)
                    .setPositiveButton(R.string.ok, null)
                    .setOnDismissListener(d -> finish())
                    .show();
            return;
        }
        if (VeloServers.conflicts(server)) {
            // Same server name, other address or other keys: never replace what Velo knows
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.velo_invite_conflict_title)
                    .setMessage(R.string.velo_invite_conflict_message)
                    .setPositiveButton(R.string.ok, null)
                    .setOnDismissListener(d -> finish())
                    .show();
            return;
        }
        final Intent intent = joinIntent(this, server, token, inviter, user);
        new MaterialAlertDialogBuilder(this)
                .setTitle(token != null && inviter != null
                        ? getString(R.string.velo_friend_invite_confirm_title, inviter)
                        : getString(R.string.velo_invite_confirm_title, server.name))
                .setMessage(getString(R.string.velo_invite_confirm_message, server.shortCode()))
                .setPositiveButton(R.string.velo_invite_join, (d, w) -> startActivity(intent))
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(d -> finish())
                .show();
    }
}
