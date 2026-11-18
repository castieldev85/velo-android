package eu.siacs.conversations.velo;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.activity.SettingsActivity;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Velo 0.20: updates from the account's own server, no store. The server publishes
 * {@code https://<server>:5443/velo-update/velo-update.json} next to the APK; the connection is
 * pinned like uploads (VeloPinning). The APK is checked against the SHA-256 in the index, and
 * Android itself refuses it unless it is signed with Velo's key. Android always asks the user to
 * confirm the installation.
 */
public final class VeloUpdater {

    public static final String EXTRA_CHECK = "velo_check_update";
    private static final String INDEX = "velo-update.json";
    private static final int PORT = 5443;
    private static final String LAST_CHECK = "velo_update_last_check";
    private static final long CHECK_EVERY_MS = 60L * 60 * 1000;
    private static final long RETRY_AFTER_MS = 10L * 60 * 1000;
    private static final String AVAILABLE_CODE = "velo_update_available_code";
    private static final String AVAILABLE_NAME = "velo_update_available_name";
    private static final String NOTIFIED_CODE = "velo_update_notified_code";
    private static final java.util.concurrent.atomic.AtomicBoolean CHECKING = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastAttempt = 0;
    private static final String CHANNEL = "velo_updates";
    private static final int NOTIFICATION_ID = 0x7e1020;

    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String file;
        public final String sha256;

        Info(final JSONObject json) {
            this.versionCode = json.optInt("versionCode", 0);
            this.versionName = json.optString("versionName", "");
            this.file = json.optString("file", "");
            this.sha256 = json.optString("sha256", "").toLowerCase(Locale.ROOT);
        }

        public boolean isNewer() {
            return versionCode > BuildConfig.VERSION_CODE
                    && file.matches("[A-Za-z0-9._-]+\\.apk")
                    && sha256.matches("[0-9a-f]{64}");
        }
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private VeloUpdater() {}

    /** Velo pubblica (store): si aggiorna dallo store, mai dal server (che ha l'APK del gruppo). */
    public static boolean enabled() {
        return !BuildConfig.VELO_PUBLIC;
    }

    @Nullable
    public static Account account(@Nullable final XmppConnectionService service) {
        if (service == null) {
            return null;
        }
        for (final Account account : service.getAccounts()) {
            if (account.isEnabled()) {
                return account;
            }
        }
        return null;
    }

    private static OkHttpClient client(final Account account) {
        // Velo 0.39.1: the account's own server record (see VeloServers.forAccount)
        return HttpConnectionManager.newBuilder(true, false)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .addNetworkInterceptor(VeloPinning.interceptor(account))
                .build();
    }

    /** Through Tor like everything else, and only to the account's server with its pinned key. */
    public static OkHttpClient client(final String server) {
        return HttpConnectionManager.newBuilder(true, false)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .addNetworkInterceptor(VeloPinning.interceptor(server))
                .build();
    }

    /** A file in the server's sticker folder, for DownloadDefaultStickers. */
    public static HttpUrl stickerUrl(final String server, final String path) {
        final HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme("https")
                .host(server)
                .port(PORT)
                .addPathSegment("velo-update")
                .addPathSegment("stickers");
        for (final String segment : path.split("/")) {
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private static HttpUrl url(final Account account, final String file) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(account.getServer())
                .port(PORT)
                .addPathSegment("velo-update")
                .addPathSegment(file)
                .build();
    }

    /** The published version, or null when the server has none. Network work: not on the UI. */
    @Nullable
    public static Info fetch(final Account account) throws IOException {
        final Request request = new Request.Builder().url(url(account, INDEX)).build();
        try (final Response response = client(account).newCall(request).execute()) {
            if (response.code() == 404) {
                return null;
            }
            final ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            return new Info(new JSONObject(body.string()));
        } catch (final org.json.JSONException e) {
            throw new IOException("bad index", e);
        }
    }

    /**
     * At most once an hour, when the account comes online or the home is open: the home button and
     * one notification per new version. A failed try counts for ten minutes only.
     */
    public static void maybeCheck(final XmppConnectionService service, final Account account) {
        if (!enabled()) {
            return;
        }
        final var prefs = service.getPreferences();
        final long now = System.currentTimeMillis();
        if (now - prefs.getLong(LAST_CHECK, 0) < CHECK_EVERY_MS
                || now - lastAttempt < RETRY_AFTER_MS
                || !CHECKING.compareAndSet(false, true)) {
            return;
        }
        lastAttempt = now;
        try {
            final Info info = fetch(account);
            final var edit = prefs.edit().putLong(LAST_CHECK, now);
            if (info != null && info.isNewer()) {
                edit.putInt(AVAILABLE_CODE, info.versionCode).putString(AVAILABLE_NAME, info.versionName);
                if (prefs.getInt(NOTIFIED_CODE, 0) != info.versionCode) {
                    edit.putInt(NOTIFIED_CODE, info.versionCode);
                    notifyAvailable(service, info);
                }
            } else {
                edit.remove(AVAILABLE_CODE).remove(AVAILABLE_NAME);
            }
            edit.apply();
        } catch (final IOException ignored) {
            // the next try decides
        } finally {
            CHECKING.set(false);
        }
    }

    /** From the home: same check, off the UI thread, when there is a profile online. */
    public static void checkFromHome(@Nullable final XmppConnectionService service) {
        if (!enabled()) {
            return;
        }
        final Account account = account(service);
        if (account == null || !account.isOnlineAndConnected()) {
            return;
        }
        final var prefs = service.getPreferences();
        final long now = System.currentTimeMillis();
        if (now - prefs.getLong(LAST_CHECK, 0) >= CHECK_EVERY_MS && now - lastAttempt >= RETRY_AFTER_MS && !CHECKING.get()) {
            new Thread(() -> maybeCheck(service, account), "velo-update-check").start();
        }
    }

    /** The version waiting to be installed, for the home button; null when there is none. */
    @Nullable
    public static String available(final Context context) {
        if (!enabled()) {
            return null;
        }
        final var prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt(AVAILABLE_CODE, 0) > BuildConfig.VERSION_CODE ? prefs.getString(AVAILABLE_NAME, null) : null;
    }

    private static void notifyAvailable(final Context context, final Info info) {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, context.getString(R.string.velo_update_channel), NotificationManager.IMPORTANCE_DEFAULT));
        }
        final Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(EXTRA_CHECK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent pending = PendingIntent.getActivity(
                context, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTIFICATION_ID, new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.velo_update_available_title))
                .setContentText(context.getString(R.string.velo_update_available_text, info.versionName))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build());
    }

    /** Downloads the APK into the cache and checks its SHA-256. Network work: not on the UI. */
    public static File download(final Context context, final Account account, final Info info,
            final Progress progress) throws IOException {
        final File dir = new File(context.getCacheDir(), "velo-update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("no cache dir");
        }
        final File[] old = dir.listFiles();
        if (old != null) {
            for (final File file : old) {
                file.delete();
            }
        }
        final File apk = new File(dir, "Velo-update.apk");
        final Request request = new Request.Builder().url(url(account, info.file)).build();
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (final Response response = client(account).newCall(request).execute()) {
            final ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            final long total = body.contentLength();
            try (final InputStream in = body.byteStream(); final OutputStream out = new FileOutputStream(apk)) {
                final byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int last = -1;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    if (total > 0) {
                        final int percent = (int) (done * 100 / total);
                        if (percent != last) {
                            last = percent;
                            progress.onProgress(percent);
                        }
                    }
                }
            }
        }
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        if (!hex.toString().equals(info.sha256)) {
            apk.delete();
            throw new IOException("checksum");
        }
        return apk;
    }

    /** Hands the APK to Android's installer; the first time Velo must be allowed to install apps. */
    public static void install(final Activity activity, final File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.velo_update_allow_install, Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        final Uri uri = FileProvider.getUriForFile(activity, FileBackend.getAuthority(activity), apk);
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
