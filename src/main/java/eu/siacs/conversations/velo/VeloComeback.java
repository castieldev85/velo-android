package eu.siacs.conversations.velo;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo 0.50, "Messaggio se non torno": when Velo has not been opened for the chosen number of
 * days, an encrypted message goes to one trusted contact. A day before, a notification says to
 * open Velo. It counts time like {@link VeloAutoWipe} (the clock that doesn't move with the date).
 * With the app lock on only a real unlock counts as "back", otherwise opening Velo does.
 * After it has gone out it switches itself off, so it is sent once.
 */
public final class VeloComeback {

    private static final String PREFERENCES = "velo_comeback";
    private static final String DAYS = "days";
    private static final String ACCOUNT = "account";
    private static final String CONTACT = "contact";
    private static final String TEXT = "text";
    private static final String SILENT_MS = "silent_ms";
    private static final String LAST_ELAPSED = "last_elapsed";
    private static final String LAST_WALL = "last_wall";
    private static final String WARNED = "warned";
    private static final String WORK = "velo_comeback";
    private static final int NOTIFICATION_ID = 33;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    public static final int[] CHOICES = {0, 2, 3, 7, 14, 30};

    private VeloComeback() {}

    public static int days(final Context context) {
        return preferences(context).getInt(DAYS, 0);
    }

    @Nullable
    public static String contact(final Context context) {
        return preferences(context).getString(CONTACT, null);
    }

    public static String text(final Context context) {
        return preferences(context).getString(TEXT, "");
    }

    public static void enable(final Context context, final int days, final Account account,
            final Jid contact, final String text) {
        restart(context)
                .putInt(DAYS, days)
                .putString(ACCOUNT, account.getUuid())
                .putString(CONTACT, contact.asBareJid().toString())
                .putString(TEXT, text)
                .commit();
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                        WORK,
                        ExistingPeriodicWorkPolicy.KEEP,
                        new PeriodicWorkRequest.Builder(Check.class, 6, TimeUnit.HOURS).build());
        cancelNotification(context);
    }

    public static void disable(final Context context) {
        preferences(context).edit().putInt(DAYS, 0).commit();
        WorkManager.getInstance(context).cancelUniqueWork(WORK);
        cancelNotification(context);
    }

    /** Velo was opened (or unlocked, with the app lock on): the count starts again. */
    public static void onOpened(final Context context) {
        if (days(context) > 0) {
            restart(context).commit();
            cancelNotification(context);
        }
    }

    /** From the worker: counts the silent time, warns a day before, sends at the end. */
    static void check(final Context context) {
        final int days = days(context);
        if (days <= 0) {
            return;
        }
        final SharedPreferences preferences = preferences(context);
        final long now = SystemClock.elapsedRealtime();
        final long wall = System.currentTimeMillis();
        final long lastElapsed = preferences.getLong(LAST_ELAPSED, now);
        final long delta =
                now >= lastElapsed
                        ? now - lastElapsed
                        : Math.max(0, wall - preferences.getLong(LAST_WALL, wall));
        final long silent = preferences.getLong(SILENT_MS, 0) + delta;
        preferences
                .edit()
                .putLong(SILENT_MS, silent)
                .putLong(LAST_ELAPSED, now)
                .putLong(LAST_WALL, wall)
                .commit();
        if (silent >= days * DAY_MS) {
            if (send(context)) {
                disable(context);
                notify(context, R.string.velo_comeback_sent);
            }
            // not sent (service down, offline): the next check tries again
            return;
        }
        if (silent >= (days - 1) * DAY_MS && !preferences.getBoolean(WARNED, false)) {
            preferences.edit().putBoolean(WARNED, true).commit();
            notify(context, R.string.velo_comeback_warning);
        }
    }

    /** @return true when the message is handed to the service (it goes out encrypted, or waits) */
    private static boolean send(final Context context) {
        final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
        if (service == null) {
            return false;
        }
        final SharedPreferences preferences = preferences(context);
        final Account account = service.findAccountByUuid(preferences.getString(ACCOUNT, ""));
        final String contact = preferences.getString(CONTACT, null);
        final String text = preferences.getString(TEXT, "");
        if (account == null || contact == null || text.trim().isEmpty()) {
            return false;
        }
        final Jid jid;
        try {
            jid = Jid.of(contact);
        } catch (final IllegalArgumentException e) {
            return false;
        }
        final Conversation conversation = service.findOrCreateConversation(account, jid, false, false);
        final int encryption = conversation.getNextEncryption();
        if (encryption == Message.ENCRYPTION_NONE) {
            // Velo never sends plaintext; keeps trying until the chat is encrypted
            Log.w(Config.LOGTAG, "comeback message held: chat not encrypted");
            return false;
        }
        service.sendMessage(new Message(conversation, text, encryption));
        Log.d(Config.LOGTAG, "comeback message handed over");
        return true;
    }

    private static void notify(final Context context, final int text) {
        final NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null) {
            return;
        }
        final Intent open = new Intent(context, ConversationsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        notifications.notify(
                NOTIFICATION_ID,
                new NotificationCompat.Builder(context, "backup")
                        .setContentTitle(context.getString(R.string.velo_comeback_title))
                        .setContentText(context.getString(text))
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(context.getString(text)))
                        .setSmallIcon(R.drawable.ic_lock_24dp)
                        .setLocalOnly(true)
                        .setAutoCancel(true)
                        .setContentIntent(
                                PendingIntent.getActivity(
                                        context, 0, open, PendingIntent.FLAG_IMMUTABLE))
                        .build());
    }

    private static void cancelNotification(final Context context) {
        final NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) {
            notifications.cancel(NOTIFICATION_ID);
        }
    }

    private static SharedPreferences.Editor restart(final Context context) {
        return preferences(context)
                .edit()
                .putLong(SILENT_MS, 0)
                .putLong(LAST_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(LAST_WALL, System.currentTimeMillis())
                .putBoolean(WARNED, false);
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static class Check extends Worker {

        public Check(@NonNull final Context context, @NonNull final WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull
        @Override
        public Result doWork() {
            VeloComeback.check(getApplicationContext());
            return Result.success();
        }
    }
}
