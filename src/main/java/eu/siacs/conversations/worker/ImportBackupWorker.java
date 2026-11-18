package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Base64;
import eu.siacs.conversations.velo.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingInputStream;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import it.velo.chat.pinnedmessage.PinnedMessage;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.FileHelper;
import eu.siacs.conversations.xmpp.Jid;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import javax.crypto.BadPaddingException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.io.InvalidCipherTextIOException;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.json.JSONException;
import org.json.JSONObject;

public class ImportBackupWorker extends Worker {

    public static final String TAG_IMPORT_BACKUP = "tag-import-backup";

    /**
     * WorkManager keeps its input {@link Data} unencrypted on disk (no_backup/androidx.work.workdb),
     * outside the encrypted database, until it prunes the finished work. The backup password
     * (often the account password) must not go there: {@code Data} only carries a random token,
     * and the password waits in memory until the worker takes it. If the process dies before
     * that, the import fails and the user enters the password again.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> PENDING_PASSWORDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String DATA_KEY_PASSWORD_TOKEN = "password_token";
    private static final String DATA_KEY_URI = "uri";
    private static final String DATA_KEY_INCLUDE_OMEMO = "omemo";

    private static final Collection<String> OMEMO_TABLE_LIST =
            Arrays.asList(
                    // PQ OMEMO2 tables (org.signal.libsignal).
                    SQLiteAxolotlStore.PREKEY_TABLENAME,         // omemo2_prekeys
                    SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,  // omemo2_signed_prekeys
                    SQLiteAxolotlStore.SESSION_TABLENAME,        // omemo2_sessions
                    SQLiteAxolotlStore.KYBER_PREKEY_TABLENAME,   // kyber_prekeys (PQXDH)
                    SQLiteAxolotlStore.KYBER_LAST_RESORT_SESSIONS_TABLENAME, // kyber_last_resort_sessions
                    SQLiteAxolotlStore.IDENTITIES_TABLENAME,     // shared trust
                    DatabaseBackend.OMEMO2_PQ_IDENTITIES_TABLE,  // own ML-DSA-87 + peer pq_ik pins
                    // Original/legacy OMEMO tables (org.whispersystems). These are
                    // also the table names used by pre-PQ (master) backups, so they
                    // MUST stay accepted or restoring an old backup would fail.
                    "sessions",
                    "prekeys",
                    "signed_prekeys");

    private static final List<String> TABLE_ALLOW_LIST =
            new ImmutableList.Builder<String>()
                    .add(
                            Account.TABLENAME,
                            Conversation.TABLENAME,
                            Message.TABLENAME,
                            "webxdc_updates",
                            "files",
                            "muted_participants",
                            PinnedMessage.TABLENAME
                    )
                    .addAll(OMEMO_TABLE_LIST)
                    .build();

    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_]+$");

    private static final int NOTIFICATION_ID = 21;

    private final String password;
    private final String passwordToken;
    private final Uri uri;
    private final boolean includeOmemo;

    private long lastNotificationUpdate = 0;

    // Columns that actually exist in our schema, per table. Backups from other clients (e.g.
    // Conversations' messages.sharedStorage, or the obsolete identities.trusted) carry columns we
    // don't have, and an insert naming an unknown column throws and aborts the whole restore.
    private final Map<String, Set<String>> tableColumns = new HashMap<>();
    private final Set<String> droppedColumns = new HashSet<>();

    public ImportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        // taken out only when the work is done, so a run the system stops and restarts in
        // this process still has it
        this.passwordToken = inputData.getString(DATA_KEY_PASSWORD_TOKEN);
        this.password = passwordToken == null ? null : PENDING_PASSWORDS.get(passwordToken);
        this.uri = Uri.parse(inputData.getString(DATA_KEY_URI));
        this.includeOmemo = inputData.getBoolean(DATA_KEY_INCLUDE_OMEMO, true);
    }

    @NonNull
    @Override
    public Result doWork() {
        setForegroundAsync(getForegroundInfo());
        if (this.password == null) {
            Log.d(Config.LOGTAG, "backup password not available (process restarted?)");
            return failure(Reason.PASSWORD_MISSING);
        }
        final Result result;
        try {
            result = importBackup(this.uri, this.password);
        } catch (final FileNotFoundException e) {
            return failure(Reason.FILE_NOT_FOUND);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "error restoring backup " + uri, e);
            final Throwable throwable = e.getCause();
            if (throwable instanceof BadPaddingException
                    || throwable instanceof InvalidCipherTextException
                    || e instanceof InvalidCipherTextIOException
                    || e instanceof ZipException) {
                return failure(Reason.DECRYPTION_FAILED);
            } else {
                return failure(Reason.GENERIC);
            }
        } finally {
            if (passwordToken != null && !isStopped()) {
                // a stopped run is rescheduled by WorkManager and needs it again
                PENDING_PASSWORDS.remove(passwordToken);
            }
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }

        return result;
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        final var notification = createImportBackupNotification(1, 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification);
        }
    }

    private Result importBackup(final Uri uri, final String password)
            throws IOException, InvalidKeySpecException {
        final var context = getApplicationContext();
        final var database = DatabaseBackend.getInstance(context);
        Log.d(Config.LOGTAG, "importing backup from " + uri);
        final Stopwatch stopwatch = Stopwatch.createStarted();
        SQLiteDatabase db;
        try {
            db = database.getWritableDatabase();
        } catch (final net.zetetic.database.sqlcipher.SQLiteNotADatabaseException e) {
            Log.d(Config.LOGTAG, "database is encrypted but we don't have the password. deleting it to proceed with import");
            DatabaseBackend.closeInstance();
            final File dbFile = context.getDatabasePath("history");
            FileHelper.secureDelete(dbFile);
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
            if (dbFile.exists()) {
                throw e;
            }
            // Re-create via getInstance so the new DB uses the current AppSettings password,
            // not the stale constructor-time password from the closed helper.
            db = DatabaseBackend.getInstance(context).getWritableDatabase();
        }
        final InputStream inputStream;
        final String path = uri.getPath();
        final long fileSize;
        if ("file".equals(uri.getScheme()) && path != null) {
            final File file = new File(path);
            inputStream = new FileInputStream(file);
            fileSize = file.length();
        } else {
            final Cursor returnCursor =
                    context.getContentResolver().query(uri, null, null, null, null);
            if (returnCursor == null) {
                fileSize = 0;
            } else {
                returnCursor.moveToFirst();
                fileSize =
                        returnCursor.getLong(
                                returnCursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                returnCursor.close();
            }
            inputStream = context.getContentResolver().openInputStream(uri);
        }
        if (inputStream == null) {
            return failure(Reason.FILE_NOT_FOUND);
        }
        final CountingInputStream countingInputStream = new CountingInputStream(inputStream);
        final DataInputStream dataInputStream = new DataInputStream(countingInputStream);
        final BackupFileHeader backupFileHeader = BackupFileHeader.read(dataInputStream);
        Log.d(Config.LOGTAG, backupFileHeader.toString());
        if ("Conversations".equals(backupFileHeader.getApp())
                || "Quicksy".equals(backupFileHeader.getApp())) {
            Log.d(Config.LOGTAG, "importing backup created by " + backupFileHeader.getApp());
        }

        final var accounts = database.getAccountJids(false);

        if (QuickConversationsService.isQuicksy() && !accounts.isEmpty()) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS);
        }

        if (accounts.contains(backupFileHeader.getJid())) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS);
        }

        final byte[] key;
        if (backupFileHeader.getVersion() >= 4) {
            key = ExportBackupWorker.getKey(password, backupFileHeader.getSalt());
        } else {
            key = ExportBackupWorker.getLegacyKey(password, backupFileHeader.getSalt());
        }

        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(
                false, new AEADParameters(new KeyParameter(key), 128, backupFileHeader.getIv()));
        // The GCM tag is only checked once the cipher stream has been read to its end, so nothing
        // may be committed before that: a truncated or modified backup must not be imported.
        try (final CipherInputStream cipherInputStream =
                new CipherInputStream(countingInputStream, cipher)) {
            final GZIPInputStream gzipInputStream = new GZIPInputStream(cipherInputStream);
            final BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8));
            final JsonReader jsonReader = new JsonReader(reader);
            if (jsonReader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new IllegalStateException("Backup file did not begin with array");
            }
            jsonReader.beginArray();
            // Created lazily elsewhere; the backup may carry rows for it.
            database.ensureOmemo2PqTablesExist();
            db.beginTransaction();
            try {
                while (jsonReader.hasNext()) {
                    if (isStopped()) {
                        return failure(Reason.GENERIC);
                    }
                    if (jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                        throw new IOException("Unexpected token in backup: " + jsonReader.peek());
                    }
                    importRow(db, jsonReader, backupFileHeader.getJid(), password);
                    if ((SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                        lastNotificationUpdate = SystemClock.elapsedRealtime();
                        updateImportBackupNotification(fileSize, countingInputStream.getCount());
                    }
                }
                jsonReader.endArray();
                if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException("Unexpected data after backup array");
                }
                ByteStreams.exhaust(gzipInputStream);
                ByteStreams.exhaust(cipherInputStream);
                updateImportBackupNotification(fileSize, countingInputStream.getCount());
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        // The FTS triggers fire on every individual INSERT during the big transaction, creating
        // thousands of tiny segments. Rebuild the index now to collapse them into one compact
        // segment so message search is fast on the first query.
        database.rebuildMessagesIndex();
        // A backup from an older build may point messages at (or bring back files into) the old
        // cache media folder; let the next service start move and relocate them again.
        android.preference.PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .edit()
                .remove(XmppConnectionService.CACHE_MIGRATED_TO_INTERNAL)
                .remove(XmppConnectionService.MEDIA_PATHS_MIGRATED_TO_INTERNAL)
                .commit();
        final Jid jid = backupFileHeader.getJid();
        final Cursor countCursor =
                db.rawQuery(
                        "select count(messages.uuid) from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid join accounts on"
                                + " conversations.accountUuid=accounts.uuid where"
                                + " accounts.username=? and accounts.server=?",
                        new String[] {jid.getLocal(), jid.getDomain().toString()});
        countCursor.moveToFirst();
        final int count = countCursor.getInt(0);
        Log.d(Config.LOGTAG, String.format("restored %d messages in %s", count, stopwatch.stop()));
        countCursor.close();
        stopBackgroundService();
        notifySuccess();
        return Result.success();
    }

    private void importRow(
            final SQLiteDatabase db,
            final JsonReader jsonReader,
            final Jid account,
            final String passphrase)
            throws IOException {
        jsonReader.beginObject();
        final String firstParameter = jsonReader.nextName();
        if (!firstParameter.equals("table")) {
            throw new IllegalStateException("Expected key 'table'");
        }
        final String table = jsonReader.nextString();
        if (!TABLE_ALLOW_LIST.contains(table)) {
            // Newer versions of other clients may export tables we don't know. Nothing is written
            // for them, so skipping is safe and keeps the rest of the backup restorable.
            Log.w(Config.LOGTAG, "skipping unrecognized table " + table + " in backup");
            while (jsonReader.hasNext()) {
                jsonReader.nextName();
                jsonReader.skipValue();
            }
            jsonReader.endObject();
            return;
        }

        if ("files".equals(table)) {
            importFile(jsonReader);
            jsonReader.endObject();
            return;
        }

        final ContentValues contentValues = new ContentValues();
        final String secondParameter = jsonReader.nextName();
        if (!secondParameter.equals("values")) {
            throw new IllegalStateException("Expected key 'values'");
        }
        jsonReader.beginObject();
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            if (COLUMN_PATTERN.matcher(name).matches()) {
                if (jsonReader.peek() == JsonToken.NULL) {
                    jsonReader.nextNull();
                    contentValues.putNull(name);
                } else if (jsonReader.peek() == JsonToken.NUMBER) {
                    contentValues.put(name, jsonReader.nextLong());
                } else {
                    String value = jsonReader.nextString();
                    if (Message.TABLENAME.equals(table) && Message.RELATIVE_FILE_PATH.equals(name)) {
                        // The app later reads, re-shares and *deletes* the file a message points
                        // at, so a backup must not be able to point it anywhere else.
                        value = confinedMessagePath(value);
                        if (value == null) {
                            contentValues.putNull(name);
                            continue;
                        }
                    }
                    contentValues.put(name, value);
                }
            } else {
                throw new IOException(String.format("Unexpected column name %s", name));
            }
        }
        jsonReader.endObject();
        jsonReader.endObject();
        final Set<String> knownColumns = knownColumns(db, table);
        for (final String name : new ArrayList<>(contentValues.keySet())) {
            if (!knownColumns.contains(name)) {
                contentValues.remove(name);
                if (droppedColumns.add(table + "." + name)) {
                    Log.d(Config.LOGTAG, "dropping unknown column " + table + "." + name);
                }
            }
        }
        if (contentValues.size() == 0) {
            Log.w(Config.LOGTAG, "skipping row without any known columns in " + table);
            return;
        }
        if (Account.TABLENAME.equals(table)) {
            final Jid jid =
                    Jid.of(
                            contentValues.getAsString(Account.USERNAME),
                            contentValues.getAsString(Account.SERVER),
                            null);
            final String password = contentValues.getAsString(Account.PASSWORD);
            if (QuickConversationsService.isQuicksy()) {
                if (!jid.getDomain().equals(Config.QUICKSY_DOMAIN)) {
                    throw new IOException("Trying to restore non Quicksy account on Quicksy");
                }
            }
            if (jid.equals(account) && passphrase.equals(password)) {
                Log.d(Config.LOGTAG, "jid and password from backup header had matching row");
            } else {
                throw new IOException("jid or password in table did not match backup");
            }
            final var keys = Account.parseKeys(contentValues.getAsString(Account.KEYS));
            final var deviceId = keys.optString(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID);
            final var importReadyKeys = new JSONObject();
            if (!Strings.isNullOrEmpty(deviceId) && this.includeOmemo) {
                try {
                    importReadyKeys.put(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID, deviceId);
                } catch (final JSONException e) {
                    Log.e(Config.LOGTAG, "error writing omemo registration id", e);
                }
            }
            contentValues.put(Account.KEYS, importReadyKeys.toString());
        }
        final long rowId;
        if (this.includeOmemo) {
            rowId = db.insertWithOnConflict(table, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);
        } else {
            if (OMEMO_TABLE_LIST.contains(table)) {
                // Contact trust is kept either way; only our own key material is dropped.
                // Keeping the peers' pq_ik pins matters: without them every peer's post-quantum
                // key would be pinned afresh on first contact after the restore.
                if ((SQLiteAxolotlStore.IDENTITIES_TABLENAME.equals(table)
                                && contentValues.getAsInteger(SQLiteAxolotlStore.OWN) == 0)
                        || (DatabaseBackend.OMEMO2_PQ_IDENTITIES_TABLE.equals(table)
                                && !DatabaseBackend.isOwnOmemo2PqRow(
                                        contentValues.getAsString(SQLiteAxolotlStore.NAME),
                                        contentValues.getAsString(
                                                SQLiteAxolotlStore.FINGERPRINT)))) {
                    rowId = db.insertWithOnConflict(table, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);
                } else {
                    Log.d(Config.LOGTAG, "skipping over omemo key material in table " + table);
                    rowId = 0;
                }
            } else {
                rowId = db.insertWithOnConflict(table, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);
            }
        }
        if (rowId == -1) {
            Log.w(Config.LOGTAG, "skipped duplicate row in " + table);
        }
    }

    private Set<String> knownColumns(final SQLiteDatabase db, final String table) {
        final Set<String> cached = tableColumns.get(table);
        if (cached != null) {
            return cached;
        }
        final Set<String> columns = new HashSet<>();
        // table is from TABLE_ALLOW_LIST, so it is safe to concatenate
        try (final Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            final int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex));
            }
        }
        tableColumns.put(table, columns);
        return columns;
    }

    private void importFile(JsonReader jsonReader) throws IOException {
        final String valuesKey = jsonReader.nextName();
        if (!"values".equals(valuesKey)) {
            throw new IllegalStateException("Expected key 'values'");
        }
        jsonReader.beginObject();
        String portablePath = null;
        int sequence = -1;
        byte[] content = null;
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            switch (name) {
                case "path":
                    portablePath = jsonReader.nextString();
                    break;
                case "sequence":
                    sequence = jsonReader.nextInt();
                    break;
                case "content":
                    content = Base64.decode(jsonReader.nextString(), Base64.NO_WRAP);
                    break;
                default:
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();

        if (portablePath != null && content != null) {
            // SECURITY: the path comes from the (possibly crafted) backup. Without this, an
            // absolute path or `${FILES}/../shared_prefs/...` would overwrite arbitrary app data.
            final File file = confine(fromPortablePath(portablePath));
            if (file == null) {
                Log.w(Config.LOGTAG, "skipping backup file outside attachment storage: " + portablePath);
                return;
            }
            final File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (OutputStream os = new FileOutputStream(file, sequence > 0)) {
                os.write(content);
            }
        }
    }

    private void stopBackgroundService() {
        final var intent = new Intent(getApplicationContext(), XmppConnectionService.class);
        getApplicationContext().stopService(intent);
    }

    private void updateImportBackupNotification(final long total, final long current) {
        final int max;
        final int progress;
        if (total == 0) {
            max = 1;
            progress = 0;
        } else {
            max = 100;
            progress = (int) (current * 100 / total);
        }
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, createImportBackupNotification(max, progress));
    }

    private Notification createImportBackupNotification(final int max, final int progress) {
        final var context = getApplicationContext();
        final var builder = new NotificationCompat.Builder(getApplicationContext(), "backup");
        builder.setContentTitle(context.getString(R.string.restoring_backup))
                .setSmallIcon(R.drawable.ic_unarchive_24dp)
                .setProgress(max, progress, max == 1 && progress == 0);
        return builder.build();
    }

    private void notifySuccess() {
        final var context = getApplicationContext();
        final var builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(context.getString(R.string.notification_restored_backup_title))
                .setContentText(context.getString(R.string.notification_restored_backup_subtitle))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_unarchive_24dp);
        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            builder.setContentText(
                    context.getString(R.string.notification_restored_backup_subtitle));
            builder.setContentIntent(
                    PendingIntent.getActivity(
                            context,
                            145,
                            new Intent(context, AccountUtils.MANAGE_ACCOUNT_ACTIVITY),
                            s()
                                    ? PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT
                                    : PendingIntent.FLAG_UPDATE_CURRENT));
        }
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID + 2, builder.build());
    }

    public static Data data(final String password, final Uri uri, final boolean includeOmemo) {
        final String token = java.util.UUID.randomUUID().toString();
        PENDING_PASSWORDS.put(token, password);
        return new Data.Builder()
                .putString(DATA_KEY_PASSWORD_TOKEN, token)
                .putString(DATA_KEY_URI, uri.toString())
                .putBoolean(DATA_KEY_INCLUDE_OMEMO, includeOmemo)
                .build();
    }

    private static Result failure(final Reason reason) {
        return Result.failure(new Data.Builder().putString("reason", reason.toString()).build());
    }

    public enum Reason {
        ACCOUNT_ALREADY_EXISTS,
        DECRYPTION_FAILED,
        FILE_NOT_FOUND,
        PASSWORD_MISSING,
        GENERIC;

        public static Reason valueOfOrGeneric(final String value) {
            if (Strings.isNullOrEmpty(value)) {
                return GENERIC;
            }
            try {
                return valueOf(value);
            } catch (final IllegalArgumentException e) {
                return GENERIC;
            }
        }
    }

    /**
     * Where restored attachments may live: the places this app stores message files (see
     * FileBackend.getStorageLocation / getLegacyStorageLocation). Nothing else under our private
     * storage (databases, shared_prefs, keys) and nothing else in shared storage.
     */
    private List<File> attachmentRoots() {
        final Context context = getApplicationContext();
        final String appName = context.getString(R.string.app_name);
        final File filesDir = context.getFilesDir();
        final ImmutableList.Builder<File> roots = new ImmutableList.Builder<>();
        roots.add(new File(filesDir, "media"));
        // Stickers (StickersMigration.getStickersDir): messages may point at them via their cid.
        roots.add(new File(filesDir, "Stickers"));
        for (final String legacy : new String[] {"Files", "Images", "Videos", "Audios"}) {
            roots.add(new File(filesDir, legacy));
        }
        roots.add(context.getCacheDir());
        roots.add(new File(Environment.getExternalStorageDirectory(), appName));
        for (final String type : new String[] {
                Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DCIM}) {
            roots.add(new File(Environment.getExternalStoragePublicDirectory(type), appName));
        }
        return roots.build();
    }

    /** The canonical form of {@code path} if it lies strictly inside an attachment root, else null. */
    private File confine(final String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        try {
            final File canonical = new File(path).getCanonicalFile();
            for (final File root : attachmentRoots()) {
                final String rootPath = root.getCanonicalPath() + File.separator;
                if (canonical.getPath().startsWith(rootPath)) {
                    return canonical;
                }
            }
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "could not canonicalize backup path " + path, e);
        }
        return null;
    }

    /**
     * A message's file path from the backup: absolute paths must stay inside the attachment
     * roots; legacy bare file names (resolved against the legacy media folders) must not contain
     * any path separators or parent references. Returns null if the path is not acceptable.
     */
    private String confinedMessagePath(final String portablePath) {
        final String path = fromPortablePath(portablePath);
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            // Keep the path as written (e.g. /sdcard/... vs /storage/emulated/0/...); only its
            // canonical form has to lie inside the roots.
            return confine(path) == null ? null : path;
        }
        if (path.contains("/") || path.contains("\\") || path.equals("..") || path.equals(".")) {
            return null;
        }
        return path;
    }

    private String fromPortablePath(String path) {
        if (path == null) return null;
        final String cacheDir = getApplicationContext().getCacheDir().getAbsolutePath();
        final String filesDir = getApplicationContext().getFilesDir().getAbsolutePath();
        final String externalDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        final String documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath();

        if (path.startsWith("${CACHE}")) {
            return cacheDir + path.substring("${CACHE}".length());
        } else if (path.startsWith("${FILES}")) {
            return filesDir + path.substring("${FILES}".length());
        } else if (path.startsWith("${EXTERNAL}")) {
            return externalDir + path.substring("${EXTERNAL}".length());
        } else if (path.startsWith("${DOCUMENTS}")) {
            return documentsDir + path.substring("${DOCUMENTS}".length());
        }
        return path;
    }
}
