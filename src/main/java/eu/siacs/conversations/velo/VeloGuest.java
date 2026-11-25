package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo 0.50, "Ospite a tempo": an account the server's admin makes for an outsider, with an end.
 *
 * <p>Admin side: the guest is remembered on the admin's phone. Once the guest's phone has an
 * encryption key, the admin's Velo sends it the end date ({@link RemoteWipe#EXPIRES}, inside the
 * same authenticated channel as the remote wipe). At the end it sends the remote wipe that deletes
 * the account when the phone answers; with no answer in a day, or when the guest never came, the
 * account is deleted on the server anyway.
 *
 * <p>Guest side: the end date is honoured only from admin@ of its own server with the pinned admin
 * key (checked by the service, as for the wipe). At the end the phone wipes itself, also offline:
 * time is counted like {@link VeloAutoWipe} (a clock the date can't move) and on the date as well,
 * whichever comes first.
 */
public final class VeloGuest {

    private static final String PREFERENCES = "velo_guest";
    private static final String GUESTS = "guests";
    private static final String OWN_ACCOUNT = "own_account";
    private static final String OWN_UNTIL = "own_until";
    private static final String OWN_LEFT_MS = "own_left_ms";
    private static final String OWN_LAST_ELAPSED = "own_last_elapsed";
    /** Up to 0.50.0 (counted once, when the date came); only removed now, see {@link #checkOwn}. */
    private static final String OWN_ONLY = "own_only";
    private static final String WORK = "velo_guest";
    static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** After the wipe is sent, how long to wait for the guest's phone before deleting anyway. */
    static final long ANSWER_WAIT_MS = DAY_MS;

    public static final int[] DAYS = {1, 3, 7, 30};

    private VeloGuest() {}

    // ---------------------------------------------------------------- admin side

    /** One guest made by an admin of this phone. */
    public static final class Guest {
        public final String admin;
        public final Jid user;
        public final long until;
        public final boolean told;
        public final long wipeSent;

        Guest(final String admin, final Jid user, final long until, final boolean told, final long wipeSent) {
            this.admin = admin;
            this.user = user;
            this.until = until;
            this.told = told;
            this.wipeSent = wipeSent;
        }

        String encode() {
            return admin + "|" + user.asBareJid() + "|" + until + "|" + (told ? 1 : 0) + "|" + wipeSent;
        }

        @Nullable
        static Guest decode(final String value) {
            final String[] parts = value.split("\\|");
            if (parts.length != 5) {
                return null;
            }
            try {
                return new Guest(parts[0], Jid.of(parts[1]), Long.parseLong(parts[2]),
                        "1".equals(parts[3]), Long.parseLong(parts[4]));
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }

        Guest told() {
            return new Guest(admin, user, until, true, wipeSent);
        }

        Guest wipeSentAt(final long when) {
            return new Guest(admin, user, until, told, when);
        }
    }

    /** What the admin's Velo should do now for one guest (pure, testable). */
    enum Step { NOTHING, TELL, WIPE, DELETE }

    /**
     * Velo 0.50.1: the guest is forgotten only once its account is really gone from the server
     * ({@link #deleteOnServer}, or the service after the phone's answer). So a guest still here
     * after the wipe is deleted on the server: the phone did not answer in a day, the admin stopped
     * waiting, or the delete after the answer failed. Before, a missing wait meant "forget", and
     * the account stayed on the server for good.
     */
    static Step next(final Guest guest, final long now, final boolean wipeStillPending) {
        if (guest.wipeSent > 0) {
            return wipeStillPending && now - guest.wipeSent < ANSWER_WAIT_MS
                    ? Step.NOTHING : Step.DELETE;
        }
        if (now >= guest.until) {
            // also a guest who never got the date: tickAdmin first tries to reach its phone
            return Step.WIPE;
        }
        return guest.told ? Step.NOTHING : Step.TELL;
    }

    /**
     * Deletes the guest's account on the server; the guest is forgotten only when that worked, or
     * when the account turns out to be gone already (then the delete has nothing left to do).
     */
    static void deleteOnServer(final Context context, final XmppConnectionService service,
            final Account admin, final Jid user) {
        VeloAdmin.run(service, admin, VeloAdmin.DELETE,
                VeloAdmin.forUser(VeloAdmin.DELETE, user, null), ok -> {
                    Log.d(Config.LOGTAG, "guest expired: account deleted " + ok);
                    if (ok) {
                        gone(context, service, admin, user);
                        return;
                    }
                    VeloAdmin.list(service, admin, VeloAdmin.LIST_USERS, "registereduserjids", all -> {
                        if (all != null && !all.contains(user.asBareJid())) {
                            gone(context, service, admin, user);
                        }
                        // otherwise the next check tries again
                    });
                });
    }

    private static void gone(final Context context, final XmppConnectionService service,
            final Account admin, final Jid user) {
        RemoteWipe.removePending(context, admin, user);
        forget(context, admin, user);
        service.updateConversationUi();
    }

    public static void addGuest(final Context context, final Account admin, final Jid user, final int days) {
        final long until = System.currentTimeMillis() + days * DAY_MS;
        put(context, new Guest(admin.getUuid(), user.asBareJid(), until, false, 0));
        schedule(context);
    }

    @Nullable
    public static Guest find(final Context context, final Account admin, final Jid user) {
        for (final Guest guest : guests(context)) {
            if (guest.admin.equals(admin.getUuid()) && guest.user.equals(user.asBareJid())) {
                return guest;
            }
        }
        return null;
    }

    public static void forget(final Context context, final Account admin, final Jid user) {
        final Set<String> all = new HashSet<>();
        for (final Guest guest : guests(context)) {
            if (!guest.admin.equals(admin.getUuid()) || !guest.user.equals(user.asBareJid())) {
                all.add(guest.encode());
            }
        }
        preferences(context).edit().putStringSet(GUESTS, all).commit();
    }

    static List<Guest> guests(final Context context) {
        final List<Guest> result = new ArrayList<>();
        for (final String value : preferences(context).getStringSet(GUESTS, new HashSet<>())) {
            final Guest guest = Guest.decode(value);
            if (guest != null) {
                result.add(guest);
            }
        }
        return result;
    }

    private static void put(final Context context, final Guest guest) {
        final Set<String> all = new HashSet<>();
        for (final Guest other : guests(context)) {
            if (!other.admin.equals(guest.admin) || !other.user.equals(guest.user)) {
                all.add(other.encode());
            }
        }
        all.add(guest.encode());
        preferences(context).edit().putStringSet(GUESTS, all).commit();
    }

    /** Runs on the worker's thread: may wait for the guest's keys. */
    private static void tickAdmin(final Context context, final XmppConnectionService service) {
        final long now = System.currentTimeMillis();
        for (final Guest guest : guests(context)) {
            final Account admin = service.findAccountByUuid(guest.admin);
            if (admin == null) {
                continue;
            }
            if (!admin.isOnlineAndConnected()) {
                continue;
            }
            final boolean pending = RemoteWipe.findPending(context, admin, guest.user) != null;
            switch (next(guest, now, pending)) {
                case TELL -> {
                    if (service.sendVeloGuestExpiry(admin, guest.user, guest.until)) {
                        put(context, guest.told());
                        Log.d(Config.LOGTAG, "guest told its end date");
                    }
                }
                case WIPE -> {
                    // Velo 0.50.1: a guest who never got the date gets it now (already past: its
                    // phone wipes itself). Only with no phone to reach is the account deleted
                    // straight away; before, it was deleted and the phone kept all the chats.
                    if (!guest.told && !service.sendVeloGuestExpiry(admin, guest.user, guest.until)) {
                        Log.d(Config.LOGTAG, "guest expired, no phone to reach: account deleted");
                        deleteOnServer(context, service, admin, guest.user);
                        break;
                    }
                    service.sendVeloRemoteWipe(admin, guest.user, RemoteWipe.MODE_DELETE);
                    put(context, guest.told().wipeSentAt(now));
                    Log.d(Config.LOGTAG, "guest expired: wipe sent");
                }
                case DELETE -> deleteOnServer(context, service, admin, guest.user);
                default -> {}
            }
        }
    }

    // ---------------------------------------------------------------- guest side

    /** The admin's end date for this phone's account, already checked by the service. */
    public static void onEndDate(final Context context, final XmppConnectionService service,
            final Account account, final long until) {
        final long left = Math.max(0, until - System.currentTimeMillis());
        preferences(context)
                .edit()
                .putString(OWN_ACCOUNT, account.getUuid())
                .putLong(OWN_UNTIL, until)
                .putLong(OWN_LEFT_MS, left)
                .putLong(OWN_LAST_ELAPSED, SystemClock.elapsedRealtime())
                .commit();
        schedule(context);
    }

    /** When this phone's guest access ends, or 0 when it is no guest. */
    public static long ownEnd(final Context context) {
        return preferences(context).getString(OWN_ACCOUNT, null) == null
                ? 0 : preferences(context).getLong(OWN_UNTIL, 0);
    }

    /** Pure: the whole app goes only when no account other than the guest's is on this phone. */
    static boolean wholeApp(final List<String> accountUuids, final String guestUuid) {
        for (final String uuid : accountUuids) {
            if (!uuid.equals(guestUuid)) {
                return false;
            }
        }
        return true;
    }

    /** Pure: whether the guest time is over, by the date or by the counted time. */
    static boolean over(final long now, final long until, final long left) {
        return now >= until || left <= 0;
    }

    /**
     * Called when the process starts and from the worker. @return true when the wipe is running
     * (the whole app; Velo then restarts empty).
     */
    public static boolean checkOwn(final Context context) {
        final SharedPreferences preferences = preferences(context);
        final String accountUuid = preferences.getString(OWN_ACCOUNT, null);
        if (accountUuid == null || !isMainProcess(context)) {
            return false;
        }
        final long now = SystemClock.elapsedRealtime();
        final long last = preferences.getLong(OWN_LAST_ELAPSED, now);
        // after a reboot the clock starts again from zero: nothing is subtracted then
        final long passed = now >= last ? now - last : 0;
        final long left = preferences.getLong(OWN_LEFT_MS, 0) - passed;
        preferences.edit().putLong(OWN_LEFT_MS, left).putLong(OWN_LAST_ELAPSED, now).commit();
        if (!over(System.currentTimeMillis(), preferences.getLong(OWN_UNTIL, Long.MAX_VALUE), left)) {
            return false;
        }
        // Velo 0.50.1: the phone's accounts are counted NOW, from the database (it needs no
        // service). Before, they were counted when the date arrived, so an account added later
        // went too in the wipe of the whole app.
        final List<String> uuids = new ArrayList<>();
        for (final Account each : DatabaseBackend.getInstance(context).getAccounts()) {
            uuids.add(each.getUuid());
        }
        if (!wholeApp(uuids, accountUuid)) {
            // other accounts on this phone stay: only the guest account goes, through the service
            final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
            if (service == null) {
                return false; // tried again by the worker once the service runs; never a blind wipe
            }
            final Account account = service.findAccountByUuid(accountUuid);
            Log.d(Config.LOGTAG, "guest time over: only the guest account goes");
            clearOwn(context);
            if (account != null) {
                service.deleteAccount(account);
            }
            return false;
        }
        Log.d(Config.LOGTAG, "guest time over: wipe");
        VeloPanic.wipeNow(context);
        return true;
    }

    private static void clearOwn(final Context context) {
        preferences(context).edit().remove(OWN_ACCOUNT).remove(OWN_UNTIL).remove(OWN_LEFT_MS)
                .remove(OWN_LAST_ELAPSED).remove(OWN_ONLY).commit();
    }

    // ---------------------------------------------------------------- both

    private static void schedule(final Context context) {
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                        WORK,
                        ExistingPeriodicWorkPolicy.KEEP,
                        new PeriodicWorkRequest.Builder(Check.class, 1, TimeUnit.HOURS).build());
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
            final Context context = getApplicationContext();
            if (checkOwn(context)) {
                return Result.success();
            }
            final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
            if (service != null) {
                tickAdmin(context, service);
            }
            return Result.success();
        }
    }
}
