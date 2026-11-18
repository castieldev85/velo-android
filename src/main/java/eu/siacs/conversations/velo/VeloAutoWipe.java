package eu.siacs.conversations.velo;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.XmppActivity;

/**
 * Velo: wipes this phone after a chosen number of days without an unlock. Off by default, and only
 * with the app lock on: without a PIN anyone holding the phone would count as its owner.
 *
 * <p>Only this phone is wiped: the peers keep their chats, a holiday must not delete theirs. A day
 * before the end a notification says to open Velo. The silent time is counted on the clock that
 * doesn't move with the date; the date is only used across a reboot, so a wrong date can't wipe a
 * phone in use nor buy time for someone holding it.
 */
public final class VeloAutoWipe {

    private static final String PREFERENCES = "velo_autowipe";
    private static final String DAYS = "days";
    private static final String SILENT_MS = "silent_ms";
    private static final String LAST_ELAPSED = "last_elapsed";
    private static final String LAST_WALL = "last_wall";
    private static final String WARNED = "warned";
    private static final String WORK = "velo_autowipe";
    private static final int NOTIFICATION_ID = 32;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final long GOODBYE_MS = 5_000;

    public static final int[] CHOICES = {0, 3, 7, 14, 30};

    private VeloAutoWipe() {}

    public static int days(final Context context) {
        return preferences(context).getInt(DAYS, 0);
    }

    public static void setDays(final Context context, final int days) {
        restart(context).putInt(DAYS, days).commit();
        final WorkManager workManager = WorkManager.getInstance(context);
        if (days > 0) {
            schedule(context);
        } else {
            workManager.cancelUniqueWork(WORK);
        }
        cancelWarning(context);
    }

    /** A real PIN was typed: the count starts again. */
    public static void onUnlocked(final Context context) {
        if (days(context) > 0) {
            restart(context).commit();
            cancelWarning(context);
        }
    }

    /** Called when the process starts and from the worker. @return true when the wipe is running */
    public static boolean check(final Context context) {
        final int days = days(context);
        if (days <= 0 || !isMainProcess(context)) {
            return false;
        }
        schedule(context);
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
            Log.d(Config.LOGTAG, "auto wipe: no unlock for " + days + " days");
            leaveServer();
            VeloPanic.wipeNow(context);
            return true;
        }
        if (silent >= (days - 1) * DAY_MS && !preferences.getBoolean(WARNED, false)) {
            preferences.edit().putBoolean(WARNED, true).commit();
            warn(context);
        }
        return false;
    }

    /** Tor runs in a process of its own, where WorkManager doesn't exist. */
    private static boolean isMainProcess(final Context context) {
        try (final BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            final String line = reader.readLine();
            return line != null && line.trim().equals(context.getPackageName());
        } catch (final IOException e) {
            return false;
        }
    }

    /** Takes this phone from the device lists if it can. The service is there only if already running. */
    private static void leaveServer() {
        final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
        if (service == null) {
            return;
        }
        boolean online = false;
        for (final Account account : service.getAccounts()) {
            if (account.isOnlineAndConnected()) {
                online = true;
                try {
                    account.getAxolotlService().forgetThisDevice();
                } catch (final RuntimeException e) {
                    Log.w(Config.LOGTAG, "auto wipe: this device stays on the list", e);
                }
            }
        }
        if (online) {
            try {
                Thread.sleep(GOODBYE_MS);
            } catch (final InterruptedException ignored) {
            }
        }
    }

    private static void schedule(final Context context) {
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                        WORK,
                        ExistingPeriodicWorkPolicy.KEEP,
                        new PeriodicWorkRequest.Builder(Check.class, 6, TimeUnit.HOURS).build());
    }

    private static void warn(final Context context) {
        final NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null) {
            return;
        }
        final Intent open = new Intent(context, ConversationsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        notifications.notify(
                NOTIFICATION_ID,
                new NotificationCompat.Builder(context, "backup")
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(R.string.velo_autowipe_warning))
                        .setSmallIcon(R.drawable.ic_lock_24dp)
                        .setLocalOnly(true)
                        .setAutoCancel(true)
                        .setContentIntent(
                                PendingIntent.getActivity(
                                        context, 0, open, PendingIntent.FLAG_IMMUTABLE))
                        .build());
    }

    private static void cancelWarning(final Context context) {
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
            VeloAutoWipe.check(getApplicationContext());
            return Result.success();
        }
    }
}
