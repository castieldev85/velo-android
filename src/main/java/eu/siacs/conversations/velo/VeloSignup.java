package eu.siacs.conversations.velo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.xmpp.Jid;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Velo 0.46 (strada C, 19/11/2026): whoever installs Velo from the store without knowing anyone can
 * still get in. The phone solves the same anti-robot puzzle as the trial page of velochat.eu
 * (SHA-256 with a number of leading zero bits, a few seconds), and the site answers with a one-use
 * invite of the public server; from there it is the usual invite flow: the person chooses user name
 * and password. The server gives at most 20 of these a day. Nothing about the person is sent.
 *
 * <p>The same puzzle, lighter, protects the reports sent from the Block dialog ({@link #report}).
 * Everything goes through Tor, like the rest of Velo.
 */
public final class VeloSignup {

    private static final String SITE = "https://velochat.eu";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    public static final String[] REASONS = {"spam", "molestie", "illegale", "altro"};

    /** Velo 0.54: the account page fills in a proposed user name and password (one tap to join). */
    public static final String EXTRA_SUGGEST = "velo_signup_suggest";

    private static final String[] ADJECTIVES = {
        "blue", "calm", "swift", "brave", "quiet", "lucky", "sunny", "misty",
        "bold", "happy", "wild", "gentle", "silver", "golden", "clever", "rapid"
    };
    private static final String[] ANIMALS = {
        "fox", "owl", "lynx", "otter", "panda", "koala", "falcon", "tiger",
        "dolphin", "raven", "wolf", "heron", "badger", "robin", "seal", "hawk"
    };

    private VeloSignup() {}

    /** A user name nobody has to invent, like "swiftotter4821": the person can still change it. */
    public static String suggestUser() {
        final java.security.SecureRandom random = new java.security.SecureRandom();
        return ADJECTIVES[random.nextInt(ADJECTIVES.length)]
                + ANIMALS[random.nextInt(ANIMALS.length)]
                + (1000 + random.nextInt(9000));
    }

    /** A strong password (same generator as the password notice), kept by Velo on the phone. */
    public static String suggestPassword() {
        return VeloPasswordActivity.generate();
    }

    /** Thrown with the code the site answered ("limite", "robot", "dati", ...). */
    static final class SiteError extends IOException {
        final String code;

        SiteError(final String code) {
            super(code);
            this.code = code;
        }
    }

    /** "Create an account" on the welcome page of the store version. */
    public static void start(final Activity activity) {
        final androidx.appcompat.app.AlertDialog wait =
                new MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.velo_signup_working)
                        .setCancelable(false)
                        .show();
        final Context app = activity.getApplicationContext();
        new Thread(
                        () -> {
                            String invite = null;
                            String code = "rete";
                            try {
                                final JSONObject answer = post(app, "iscrizione", "/api/iscrizione", new JSONObject());
                                invite = answer.optString("invito", "");
                                if (!invite.startsWith("velo:invite?")) {
                                    invite = null;
                                    code = "server";
                                }
                            } catch (final SiteError e) {
                                code = e.code;
                            } catch (final Exception e) {
                                Log.d(Config.LOGTAG, "VeloSignup: iscrizione non riuscita (" + e.getClass().getSimpleName() + ")");
                            }
                            final String found = invite;
                            final String why = code;
                            activity.runOnUiThread(
                                    () -> {
                                        if (activity.isFinishing() || activity.isDestroyed()) {
                                            return;
                                        }
                                        wait.dismiss();
                                        final Intent join = found == null ? null : joinIntent(activity, found);
                                        if (join != null) {
                                            activity.startActivity(join);
                                            return;
                                        }
                                        new MaterialAlertDialogBuilder(activity)
                                                .setTitle("limite".equals(why)
                                                        ? R.string.velo_signup_limit_title
                                                        : R.string.velo_signup_error_title)
                                                .setMessage("limite".equals(why)
                                                        ? R.string.velo_signup_limit
                                                        : R.string.velo_signup_error)
                                                .setPositiveButton(R.string.ok, null)
                                                .show();
                                    });
                        },
                        "velo-signup")
                .start();
    }

    /**
     * The login page for the site's invite, without the verification-code dialog of an invite from
     * a person: this one came over HTTPS (certificate checked) straight from velochat.eu. Its
     * server must not clash with what Velo already knows; null otherwise.
     */
    @Nullable
    static Intent joinIntent(final Activity activity, final String invite) {
        final String token = VeloFriendInvite.token(invite);
        final VeloServers.Server server;
        try {
            server = VeloServers.parseInvite(invite);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (token == null || VeloServers.conflicts(server)) {
            return null;
        }
        return VeloInviteActivity.joinIntent(activity, server, token, null, null)
                .putExtra(EXTRA_SUGGEST, true);
    }

    /**
     * Whether blocking {@code jid} from {@code account} can also send a report: store version, a
     * user of the account's own server. Only velochat.eu's server takes the report: for any other
     * server the site answers "not ours" and Velo says the report was not sent. The app keeps no
     * server address of its own (the store version has no built-in server).
     */
    public static boolean canReport(@Nullable final Jid account, @Nullable final Jid jid) {
        return eu.siacs.conversations.BuildConfig.VELO_PUBLIC
                && account != null
                && jid != null
                && jid.getLocal() != null
                && account.getDomain().toString().equalsIgnoreCase(jid.getDomain().toString());
    }

    /**
     * Asks why, then sends the report and runs {@code then} (the block itself). Without a reason the
     * block still happens, unreported. Whoever reports stays anonymous: only the reported name goes.
     */
    public static void report(final Activity activity, final Jid jid, final Runnable then) {
        final CharSequence[] labels = {
            activity.getString(R.string.velo_report_spam),
            activity.getString(R.string.velo_report_harassment),
            activity.getString(R.string.velo_report_illegal),
            activity.getString(R.string.velo_report_other)
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_report_title)
                .setItems(labels, (d, which) -> {
                    send(activity, jid.asBareJid().toString(), REASONS[which]);
                    then.run();
                })
                .setNegativeButton(R.string.velo_report_skip, (d, w) -> then.run())
                .setCancelable(false)
                .show();
    }

    private static void send(final Activity activity, final String user, final String reason) {
        final Context app = activity.getApplicationContext();
        Toast.makeText(app, R.string.velo_report_sending, Toast.LENGTH_SHORT).show();
        new Thread(
                        () -> {
                            @StringRes int result = R.string.velo_report_failed;
                            try {
                                final JSONObject body = new JSONObject();
                                body.put("utente", user);
                                body.put("motivo", reason);
                                post(app, "segnala", "/api/segnala", body);
                                result = R.string.velo_report_sent;
                            } catch (final Exception e) {
                                Log.d(Config.LOGTAG, "VeloSignup: segnalazione non inviata (" + e.getClass().getSimpleName() + ")");
                            }
                            final int shown = result;
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .post(() -> Toast.makeText(app, shown, Toast.LENGTH_LONG).show());
                        },
                        "velo-report")
                .start();
    }

    /** Fetches a puzzle of the given kind, solves it and posts {@code body} with the answer. */
    private static JSONObject post(final Context app, final String kind, final String path, final JSONObject body)
            throws Exception {
        VeloTor.start(app);
        VeloTor.awaitReady(app, 90_000);
        final OkHttpClient client =
                HttpConnectionManager.newBuilder(true, false)
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(200, TimeUnit.SECONDS)
                        .build();
        final JSONObject puzzle;
        try (final Response response =
                client.newCall(new Request.Builder().url(SITE + "/api/sfida?tipo=" + kind).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("sfida " + response.code());
            }
            puzzle = new JSONObject(response.body().string());
        }
        final String challenge = puzzle.getString("sfida");
        final int bits = puzzle.getInt("bit");
        if (bits < 1 || bits > 28) {
            throw new IOException("bit " + bits);
        }
        body.put("sfida", challenge);
        body.put("nonce", Long.toString(solve(challenge, bits)));
        try (final Response response =
                client.newCall(
                                new Request.Builder()
                                        .url(SITE + path)
                                        .post(RequestBody.create(body.toString(), JSON))
                                        .build())
                        .execute()) {
            final String text = response.body() == null ? "" : response.body().string();
            final JSONObject answer = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (!response.isSuccessful()) {
                throw new SiteError(answer.optString("codice", "server"));
            }
            return answer;
        }
    }

    /** The smallest n such that SHA-256("challenge:n") starts with {@code bits} zero bits. */
    static long solve(final String challenge, final int bits) throws Exception {
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        final byte[] prefix = (challenge + ":").getBytes(StandardCharsets.US_ASCII);
        for (long n = 0; n < 1_000_000_000_000L; n++) {
            sha.reset();
            sha.update(prefix);
            sha.update(Long.toString(n).getBytes(StandardCharsets.US_ASCII));
            if (zeroBits(sha.digest()) >= bits) {
                return n;
            }
        }
        throw new IOException("no solution");
    }

    static int zeroBits(final byte[] digest) {
        int n = 0;
        for (final byte b : digest) {
            if (b == 0) {
                n += 8;
                continue;
            }
            return n + Integer.numberOfLeadingZeros(b & 0xff) - 24;
        }
        return n;
    }
}
