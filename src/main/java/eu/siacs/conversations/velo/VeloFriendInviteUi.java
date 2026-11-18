package eu.siacs.conversations.velo;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.BarcodeProvider;
import eu.siacs.conversations.ui.XmppActivity;

/**
 * Velo 0.42: the "Invite a friend" dialog. Asks the server for an invite (see VeloFriendInvite),
 * then shows the link as a QR code, with a button to send it with any app.
 */
public final class VeloFriendInviteUi {

    private VeloFriendInviteUi() {}

    public static void start(final XmppActivity activity) {
        VeloStories.withAccount(activity, account -> create(activity, account));
    }

    private static void create(final XmppActivity activity, final Account account) {
        if (activity.xmppConnectionService == null) {
            return;
        }
        final VeloServers.Server server = VeloServers.forAccount(account);
        if (server == null || server.onion == null || server.pins.isEmpty()) {
            Toast.makeText(activity, R.string.velo_friend_invite_failed, Toast.LENGTH_LONG).show();
            return;
        }
        final AlertDialog waiting = new MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.velo_friend_invite_working)
                .setCancelable(true)
                .show();
        VeloFriendInvite.create(activity.xmppConnectionService, account, result ->
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (!waiting.isShowing()) {
                        return; // the user gave up waiting
                    }
                    waiting.dismiss();
                    if (result.token != null) {
                        show(activity, VeloFriendInvite.link(server, result.token, account.getJid().getLocal()));
                    } else {
                        final int message;
                        if (result.limitReached()) {
                            message = R.string.velo_friend_invite_limit;
                        } else if (result.notAllowed()) {
                            message = R.string.velo_friend_invite_not_allowed;
                        } else if ("offline".equals(result.error)) {
                            message = R.string.velo_friend_invite_offline;
                        } else {
                            message = R.string.velo_friend_invite_failed;
                        }
                        Log.d("velo", "friend invite not created: " + result.error);
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.velo_friend_invite)
                                .setMessage(message)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }
                }));
    }

    private static void show(final XmppActivity activity, final String link) {
        final float density = activity.getResources().getDisplayMetrics().density;
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(24 * density);
        box.setPadding(pad, Math.round(8 * density), pad, 0);
        final TextView text = new TextView(activity);
        text.setText(R.string.velo_friend_invite_text);
        text.setTextSize(15);
        box.addView(text);
        final ImageView qr = new ImageView(activity);
        final int size = Math.round(220 * density);
        qr.setImageBitmap(BarcodeProvider.create2dBarcodeBitmap(link, size, 0xFF000000, 0xFFFFFFFF));
        qr.setContentDescription("velo_friend_invite_qr");
        final LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(size, size);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.topMargin = Math.round(12 * density);
        box.addView(qr, qrParams);
        final TextView expiry = new TextView(activity);
        expiry.setText(R.string.velo_friend_invite_expiry);
        expiry.setTextSize(13);
        expiry.setGravity(Gravity.CENTER_HORIZONTAL);
        expiry.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        final LinearLayout.LayoutParams expiryParams = new LinearLayout.LayoutParams(-1, -2);
        expiryParams.topMargin = Math.round(8 * density);
        box.addView(expiry, expiryParams);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_friend_invite)
                .setView(box)
                .setPositiveButton(R.string.velo_friend_invite_share, (d, w) -> share(activity, link))
                .setNegativeButton(R.string.velo_friend_invite_close, null)
                .show();
    }

    private static void share(final XmppActivity activity, final String link) {
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.velo_friend_invite_share_text, link));
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.velo_friend_invite_share)));
        } catch (final android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.velo_friend_invite_failed, Toast.LENGTH_LONG).show();
        }
    }
}
