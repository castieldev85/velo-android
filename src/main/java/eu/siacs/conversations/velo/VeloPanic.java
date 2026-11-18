package eu.siacs.conversations.velo;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Velo: the panic PIN. Typed on the lock screen instead of the real PIN, it deletes in silence:
 *
 * <ol>
 *   <li>the mark that a wipe is under way: if the phone goes off now, Velo finishes the job as soon
 *       as Android starts it again, before anything is shown;
 *   <li>what lives outside the app: Documents/Velo, the old folder, what was saved to Download;
 *   <li>every chat on this phone, at once, with what the peers' phones must delete put in a queue;
 *   <li>if this phone reaches the server: every 1:1 chat on the peers' phones (the purge for Velo
 *       peers, a retraction of our own messages for the others), then this phone from the account's
 *       device lists, giving it a few seconds to leave;
 *   <li>all of Velo's data on this phone: chats, files, keys, account, settings. Android closes
 *       the app; opened again it is a fresh install.
 * </ol>
 *
 * Without an answer from the server (no network, or a connection that died in silence) the last step
 * waits: account, keys and contacts stay, with no message, until the network is back or {@link
 * #OFFLINE_WINDOW_MS} has passed. Velo then shows nothing: opening it wipes everything at once, and
 * the peers keep their copies. If the phone is wiped before the network returns they keep them too.
 *
 * The account stays on the server (one can log in again); the chats are gone for good. Files saved
 * by hand outside Download, and what other apps copied, are out of reach.
 */
public final class VeloPanic {

    /** How long the purges and retractions get to go out before everything is wiped. */
    private static final long SEND_TIME_MS = 15_000;

    /** How long account and keys may wait for a network, after a panic PIN typed offline. */
    private static final long OFFLINE_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /** How long one run waits for the account to come online through Tor. */
    private static final long ONLINE_WAIT_MS = 150_000;

    /** How long, once online, for the server to tell which phones the account has. */
    private static final long DEVICE_LIST_WAIT_MS = 20_000;

    private static final String PREFERENCES = "velo_panic";
    private static final String PENDING = "pending";
    private static final String WAITING_UNTIL = "waiting_until";
    private static final String SAVED = "saved_files";

    private VeloPanic() {}

    public static void trigger(final Activity activity) {
        final Context context = activity.getApplicationContext();
        preferences(context).edit().putBoolean(PENDING, true).commit();
        final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
        // Nothing on screen tells what is happening: Velo just closes, with no notification
        activity.moveTaskToBack(true);
        if (service != null) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }
        new Thread(
                        () -> {
                            deleteOutsideApp(context);
                            final NotificationManager notifications = context.getSystemService(NotificationManager.class);
                            if (notifications != null) {
                                notifications.cancelAll();
                            }
                            if (service == null) {
                                wipe(context);
                                return;
                            }
                            try {
                                service.clearAllChatsHereAndQueueForPeers();
                            } catch (final RuntimeException e) {
                                Log.w(Config.LOGTAG, "panic: clearing the chats failed", e);
                            }
                            if (sendAndCheck(service)) {
                                closeSessions(service);
                                wipe(context);
                            } else {
                                startWaiting(context, activity, service);
                            }
                        },
                        "velo-panic")
                .start();
    }

    /**
     * Called first thing whenever a process of Velo starts: a wipe that was interrupted ends here.
     *
     * @return true when the wipe runs, and nothing else should start
     */
    public static boolean finishIfInterrupted(final Context context) {
        if (!preferences(context).getBoolean(PENDING, false)) {
            return false;
        }
        deleteOutsideApp(context);
        wipe(context);
        return true;
    }

    /** Whether a panic PIN typed offline is waiting for a network. */
    public static boolean isWaiting(final Context context) {
        return preferences(context).getLong(WAITING_UNTIL, 0) != 0;
    }

    /** While waiting Velo is gone for the person holding the phone: opening it wipes it for real. */
    public static boolean wipeIfWaiting(final Context context) {
        if (!isWaiting(context)) {
            return false;
        }
        wipeNow(context.getApplicationContext());
        return true;
    }

    /** Velo 0.41: a wipe is under way (remote wipe); if Velo stops now, the next start finishes it. */
    public static void markPending(final Context context) {
        preferences(context).edit().putBoolean(PENDING, true).commit();
    }

    /** Everything on this phone goes now and nothing is sent. */
    public static void wipeNow(final Context context) {
        new Thread(
                        () -> {
                            deleteOutsideApp(context);
                            wipe(context);
                        },
                        "velo-wipe")
                .start();
    }

    /**
     * The purges are out when the server answers after them: a connection that died in silence still
     * says it is online. Nothing leaves the queue here, so a dead connection loses nothing.
     */
    private static boolean sendAndCheck(final XmppConnectionService service) {
        if (!anyOnline(service)) {
            return false;
        }
        final long start = SystemClock.elapsedRealtime();
        try {
            service.flushVeloChatDeletions(true);
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "panic: purging the peers failed", e);
        }
        forgetThisDevice(service);
        for (final Account account : service.getAccounts()) {
            final var connection = account.getXmppConnection();
            if (connection != null && account.isOnlineAndConnected()) {
                connection.sendPing();
            }
        }
        final long left = SEND_TIME_MS - (SystemClock.elapsedRealtime() - start);
        if (left > 0) {
            try {
                Thread.sleep(left);
            } catch (final InterruptedException ignored) {
            }
        }
        for (final Account account : service.getAccounts()) {
            final var connection = account.getXmppConnection();
            if (connection != null
                    && account.isOnlineAndConnected()
                    && connection.getLastPacketReceived() > start) {
                return true;
            }
        }
        return false;
    }

    private static void startWaiting(
            final Context context, final Activity activity, final XmppConnectionService service) {
        preferences(context)
                .edit()
                .putLong(WAITING_UNTIL, System.currentTimeMillis() + OFFLINE_WINDOW_MS)
                .putBoolean(PENDING, false)
                .commit();
        VeloPanicWorker.schedule(context, OFFLINE_WINDOW_MS);
        activity.runOnUiThread(activity::finishAndRemoveTask);
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        context.stopService(new Intent(context, XmppConnectionService.class));
    }

    /**
     * One run of {@link VeloPanicWorker}.
     *
     * @return false to be run again later
     */
    static boolean finishWaiting(final Context context) {
        if (!isWaiting(context)) {
            return true;
        }
        if (System.currentTimeMillis() >= preferences(context).getLong(WAITING_UNTIL, 0)) {
            deleteOutsideApp(context);
            wipe(context);
            return true;
        }
        final CountDownLatch bound = new CountDownLatch(1);
        final XmppConnectionService[] service = new XmppConnectionService[1];
        final ServiceConnection connection =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(final ComponentName name, final IBinder binder) {
                        service[0] = ((XmppConnectionService.XmppConnectionBinder) binder).getService();
                        bound.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(final ComponentName name) {}
                };
        if (!context.bindService(
                new Intent(context, XmppConnectionService.class), connection, Context.BIND_AUTO_CREATE)) {
            return false;
        }
        try {
            final long end = SystemClock.elapsedRealtime() + ONLINE_WAIT_MS;
            if (!bound.await(ONLINE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return false;
            }
            while (!anyOnline(service[0])) {
                if (SystemClock.elapsedRealtime() > end) {
                    return false;
                }
                Thread.sleep(2000);
            }
            for (final Account account : service[0].getAccounts()) {
                if (account.isOnlineAndConnected() && !account.getAxolotlService().knowsOwnDeviceList()) {
                    account.getAxolotlService().fetchDeviceIds(account.getJid().asBareJid());
                }
            }
            final long listEnd = SystemClock.elapsedRealtime() + DEVICE_LIST_WAIT_MS;
            while (!knowsOwnDeviceLists(service[0]) && SystemClock.elapsedRealtime() < listEnd) {
                Thread.sleep(1000);
            }
            if (!sendAndCheck(service[0])) {
                return false;
            }
            closeSessions(service[0]);
            deleteOutsideApp(context);
            wipe(context);
            return true;
        } catch (final InterruptedException e) {
            return false;
        } finally {
            try {
                context.unbindService(connection);
            } catch (final RuntimeException ignored) {
            }
        }
    }

    /** Velo keeps the path of what it saves to Download, to delete it with the rest. */
    public static void rememberSaved(final Context context, final File file) {
        final SharedPreferences preferences = preferences(context);
        final Set<String> saved = new HashSet<>(preferences.getStringSet(SAVED, new HashSet<>()));
        saved.add(file.getAbsolutePath());
        preferences.edit().putStringSet(SAVED, saved).apply();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean anyOnline(final XmppConnectionService service) {
        for (final Account account : service.getAccounts()) {
            if (account.isOnlineAndConnected()) {
                return true;
            }
        }
        return false;
    }

    private static boolean knowsOwnDeviceLists(final XmppConnectionService service) {
        for (final Account account : service.getAccounts()) {
            if (account.isOnlineAndConnected() && !account.getAxolotlService().knowsOwnDeviceList()) {
                return false;
            }
        }
        return true;
    }

    private static void forgetThisDevice(final XmppConnectionService service) {
        for (final Account account : service.getAccounts()) {
            if (account.isOnlineAndConnected()) {
                try {
                    account.getAxolotlService().forgetThisDevice();
                } catch (final RuntimeException e) {
                    Log.w(Config.LOGTAG, "panic: this device stays on the list", e);
                }
            }
        }
    }

    /**
     * Says goodbye to the server. A session that just vanishes stays open there for minutes, waiting
     * for us to resume it, and what it held goes back to the senders as "not delivered" when it ends.
     */
    private static void closeSessions(final XmppConnectionService service) {
        for (final Account account : service.getAccounts()) {
            final var connection = account.getXmppConnection();
            if (connection != null) {
                try {
                    connection.disconnect(false);
                } catch (final RuntimeException e) {
                    Log.w(Config.LOGTAG, "panic: session not closed", e);
                }
            }
        }
    }

    private static void deleteOutsideApp(final Context context) {
        final File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        deleteRecursively(new File(documents, context.getString(R.string.app_name)));
        deleteRecursively(FileBackend.getBackupDirectory(context).getParentFile());
        for (final String path : preferences(context).getStringSet(SAVED, new HashSet<>())) {
            deleteRecursively(new File(path));
        }
    }

    private static void wipe(final Context context) {
        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager != null && activityManager.clearApplicationUserData()) {
            return; // Android deletes everything and stops the app
        }
        // Fallback: delete what we can reach, then stop. The mark goes with the rest.
        deleteRecursively(context.getFilesDir().getParentFile());
        deleteRecursively(context.getExternalFilesDir(null));
        deleteRecursively(context.getExternalCacheDir());
        Process.killProcess(Process.myPid());
    }

    private static void deleteRecursively(final File file) {
        if (file == null || !file.exists()) {
            return;
        }
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
