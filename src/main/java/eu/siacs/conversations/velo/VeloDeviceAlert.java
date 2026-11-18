package eu.siacs.conversations.velo;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.EditAccountActivity;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo: when a contact starts using a new device (a new OMEMO identity key next to the ones we
 * already knew), the chat shows it. Tapping the note opens the contact with that key highlighted,
 * ready to be verified by QR. Keys are still trusted blindly until a contact has a verified one
 * (the upstream project's default); this note is what makes a stolen account or a key slipped in by the server
 * visible.
 */
public final class VeloDeviceAlert {

    private static final String ID_PREFIX = "velo-new-device:";
    private static final String OWN_SEEN_PREFIX = "velo_own_device_seen_";
    private static final String CHANNEL = "velo_own_device";
    private static final int NOTIFICATION_ID = 0x7e10;
    private static final String ADDED_PREFIX = "velo_account_added_";
    private static final long FIRST_LOGIN_GRACE_MS = 3 * 60_000L;
    private static final String LIST_RESET_PREFIX = "velo_device_list_reset_";
    public static final String UNCONFIRMED_ERROR = "velo-unconfirmed-device";

    private VeloDeviceAlert() {}

    /** Called when a key is stored for {@code jid} that wasn't there, while other keys were. */
    public static void newDevice(
            final XmppConnectionService service,
            final Account account,
            final String jid,
            final String fingerprint) {
        final Jid contactJid;
        try {
            contactJid = Jid.of(jid).asBareJid();
        } catch (final IllegalArgumentException e) {
            return;
        }
        if (contactJid.equals(account.getJid().asBareJid())) {
            ownDevice(service, account, fingerprint);
            return;
        }
        final Conversation conversation = service.find(account, contactJid);
        if (conversation == null || conversation.getMode() != Conversation.MODE_SINGLE) {
            return;
        }
        final String id = ID_PREFIX + fingerprint;
        if (conversation.findMessageWithRemoteId(id, contactJid) != null) {
            return;
        }
        final String name = conversation.getContact().getDisplayName();
        final Message note =
                new Message(
                        conversation,
                        service.getString(R.string.velo_new_device, name),
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        note.setType(Message.TYPE_STATUS);
        note.setCounterpart(contactJid);
        note.setRemoteMsgId(id);
        conversation.add(note);
        service.databaseBackend.createMessage(note);
        service.updateConversationUi();
    }

    /** When an account is added on this phone; its first half hour of own devices is not news. */
    public static void accountAdded(final Context context, final Account account) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putLong(ADDED_PREFIX + account.getJid().asBareJid(), System.currentTimeMillis())
                .remove(LIST_RESET_PREFIX + account.getJid().asBareJid())
                .apply();
    }

    /** When the account was added on this phone; 0 when added before Velo kept the time. */
    public static long addedAt(final Context context, final Account account) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(ADDED_PREFIX + account.getJid().asBareJid(), 0);
    }

    /**
     * Whether this phone still has to announce itself as the account's only device: on the first
     * login of the account here, and once after the update that brought this in, so the lists
     * already full of dead phones get cleaned too. See AxolotlService#publishOmemo2DeviceId.
     */
    public static boolean needsDeviceListReset(final Context context, final Account account) {
        return !PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(LIST_RESET_PREFIX + account.getJid().asBareJid(), false);
    }

    /** The server accepted the list with only this device. */
    public static void deviceListReset(final Context context, final Account account) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(LIST_RESET_PREFIX + account.getJid().asBareJid(), true)
                .apply();
    }

    /**
     * Error stored on a message that was not sent because {@code owner} has a device nobody
     * confirmed. The part before \u001f is the marker MessageAdapter looks for.
     */
    public static String unconfirmedError(
            final Context context, final Message message, final Jid owner) {
        final Conversation conversation = (Conversation) message.getConversation();
        final Account account = conversation.getAccount();
        final String name;
        if (owner.equals(account.getJid().asBareJid())) {
            name = context.getString(R.string.velo_your_account);
        } else {
            name = account.getRoster().getContact(owner).getDisplayName();
        }
        return UNCONFIRMED_ERROR + "\u001f" + context.getString(R.string.velo_not_sent_unconfirmed, name);
    }

    public static boolean isUnconfirmedError(final String errorMessage) {
        return errorMessage != null && errorMessage.startsWith(UNCONFIRMED_ERROR + "\u001f");
    }

    /**
     * A new device on the user's own account receives a copy of every message from then on, so
     * it gets a notification, not a quiet note: it is either the user's new phone or someone
     * (a stolen password, the server) reading along. Tapping it opens the account's keys.
     */
    private static void ownDevice(
            final XmppConnectionService service, final Account account, final String fingerprint) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(service);
        final String seen = OWN_SEEN_PREFIX + fingerprint;
        if (prefs.getBoolean(seen, false)) {
            return;
        }
        prefs.edit().putBoolean(seen, true).apply();
        // Right after logging in on this phone, the account's other devices show up for the
        // first time: those are the user's own, not news.
        final long added = prefs.getLong(ADDED_PREFIX + account.getJid().asBareJid(), 0);
        if (System.currentTimeMillis() - added < FIRST_LOGIN_GRACE_MS) {
            return;
        }
        final NotificationManager manager = service.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, service.getString(R.string.velo_own_device_channel),
                    NotificationManager.IMPORTANCE_HIGH));
        }
        final Intent intent = new Intent(service, EditAccountActivity.class)
                .putExtra("jid", account.getJid().asBareJid().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pending = PendingIntent.getActivity(
                service, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTIFICATION_ID, new NotificationCompat.Builder(service, CHANNEL)
                .setSmallIcon(R.drawable.ic_warning_24dp)
                .setContentTitle(service.getString(R.string.velo_own_device_title))
                .setContentText(service.getString(R.string.velo_own_device_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(service.getString(R.string.velo_own_device_text)))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build());
    }

    /** The fingerprint a new-device note is about, or null for any other message. */
    public static String fingerprintOf(final Message message) {
        final String id = message.getRemoteMsgId();
        return id != null && id.startsWith(ID_PREFIX) ? id.substring(ID_PREFIX.length()) : null;
    }
}
