package eu.siacs.conversations.velo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.ScanActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.services.BarcodeProvider;
import eu.siacs.conversations.worker.ExportBackupWorker;
import eu.siacs.conversations.worker.ImportBackupWorker;

/**
 * Velo: moves chats, keys and files to a new phone over the local network, with no server between
 * them. The old phone goes offline before the copy starts, so that nothing arrives after it, and is
 * wiped only when the new one says it has everything. Two phones with the same keys must never be
 * online together: their encrypted sessions would break.
 */
public class VeloTransferActivity extends XmppActivity {

    private static final String EXTRA_RECEIVE = "receive";
    private static final int ACCEPT_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int READ_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int MAX_WRONG_KNOCKS = 5;
    private static final byte[] HELLO = "velo-hello".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DONE = "velo-done".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FAILED = "velo-failed".getBytes(StandardCharsets.UTF_8);
    private static final byte TYPE_START = 1;
    private static final byte TYPE_DATA = 2;
    private static final byte TYPE_END = 3;
    private static final byte TYPE_ALL_DONE = 4;
    private static final String ROOT_BACKUP = "backup";
    private static final String ROOT_INTERNAL = "internal";
    private static final String ROOT_DOCUMENTS = "documents";

    private LinearLayout body;
    private boolean receive;
    private boolean started = false;
    private volatile boolean closing = false;
    private volatile boolean accountsOff = false;
    private volatile boolean movedAway = false;
    private volatile boolean waitingForAnswer = false;
    private volatile ServerSocket server;
    private volatile Socket socket;

    public static void startSending(final Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_transfer_title)
                .setMessage(R.string.velo_transfer_warning)
                .setPositiveButton(
                        R.string.velo_transfer_continue,
                        (d, w) -> activity.startActivity(new Intent(activity, VeloTransferActivity.class)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void startReceiving(final Activity activity) {
        activity.startActivity(new Intent(activity, VeloTransferActivity.class).putExtra(EXTRA_RECEIVE, true));
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_velo_diagnostics);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.velo_diagnostics_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        Activities.setStatusAndNavigationBarColors(this, findViewById(R.id.velo_diagnostics_root));
        body = findViewById(R.id.velo_diagnostics_body);
        receive = getIntent().getBooleanExtra(EXTRA_RECEIVE, false);
        toolbar.setTitle(receive ? R.string.velo_transfer_receive_title : R.string.velo_transfer_title);
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    public void onBackendConnected() {
        if (started) {
            return;
        }
        started = true;
        if (receive) {
            showCodeEntry();
        } else {
            new Thread(this::send, "velo-transfer-send").start();
        }
    }

    @Override
    protected void onDestroy() {
        closing = true;
        close(server);
        close(socket);
        restoreAccounts();
        super.onDestroy();
    }

    private void restoreAccounts() {
        if (!accountsOff || movedAway || waitingForAnswer || xmppConnectionService == null) {
            return;
        }
        accountsOff = false;
        for (final Account account : xmppConnectionService.getAccounts()) {
            account.setOption(Account.OPTION_DISABLED, false);
            xmppConnectionService.databaseBackend.updateAccount(account);
            xmppConnectionService.reconnectAccountInBackground(account);
        }
    }

    // ---- the old phone

    private void send() {
        File directory = null;
        try {
            final String host = VeloTransfer.localAddress();
            if (host == null) {
                fail(R.string.velo_transfer_no_wifi);
                return;
            }
            server = new ServerSocket();
            server.setSoTimeout(ACCEPT_TIMEOUT_MS);
            server.bind(new InetSocketAddress(0));
            final VeloTransfer.Code code = VeloTransfer.newCode(host, server.getLocalPort());
            runOnUiThread(() -> showCode(code.toString()));
            final Socket accepted = acceptNewPhone(code);
            if (accepted == null) {
                return;
            }
            status(R.string.velo_transfer_preparing);
            goOffline();
            directory = new File(getCacheDir(), "velo-transfer");
            deleteRecursively(directory);
            if (!directory.mkdirs() || !exportBackups(directory)) {
                throw new IOException("backup not created");
            }
            final VeloTransfer.Channel channel = channelOn(accepted, code.key, true);
            sendEverything(channel, directory);
            status(R.string.velo_transfer_waiting_new_phone);
            final byte[] answer;
            try {
                answer = channel.receive();
            } catch (final IOException e) {
                // It may have everything and be online already: this phone must not come back by itself
                Log.w(Config.LOGTAG, "transfer: no answer from the new phone", e);
                if (!closing) {
                    runOnUiThread(this::askWhatHappened);
                }
                return;
            }
            if (!java.util.Arrays.equals(answer, DONE)) {
                throw new IOException("the new phone did not finish");
            }
            wipeThisPhone();
        } catch (final IOException | InterruptedException | ExecutionException | JSONException e) {
            if (!closing) {
                Log.w(Config.LOGTAG, "transfer: sending failed", e);
                restoreAccounts();
                fail(R.string.velo_transfer_failed_old);
            }
        } finally {
            if (!movedAway && directory != null) {
                deleteRecursively(directory);
            }
            close(server);
            close(socket);
        }
    }

    private void wipeThisPhone() {
        movedAway = true;
        status(R.string.velo_transfer_wiping);
        deleteRecursively(new File(getCacheDir(), "velo-transfer"));
        VeloPanic.wipeNow(getApplicationContext());
    }

    /** Only the person holding both phones knows. Until then this one stays offline. */
    private void askWhatHappened() {
        waitingForAnswer = true;
        body.removeAllViews();
        addText(getString(R.string.velo_transfer_no_answer), 16);
        addButton(R.string.velo_transfer_it_has_everything)
                .setOnClickListener(v -> new Thread(this::wipeThisPhone, "velo-transfer-wipe").start());
        addButton(R.string.velo_transfer_it_failed)
                .setOnClickListener(
                        v -> {
                            waitingForAnswer = false;
                            restoreAccounts();
                            finish();
                        });
    }

    /** Whoever doesn't have the key is sent away; after a few of them the door closes. */
    private Socket acceptNewPhone(final VeloTransfer.Code code) throws IOException {
        for (int knocks = 0; knocks < MAX_WRONG_KNOCKS && !closing; knocks++) {
            final Socket candidate;
            try {
                candidate = server.accept();
            } catch (final SocketTimeoutException e) {
                fail(R.string.velo_transfer_expired);
                return null;
            }
            candidate.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket = candidate;
            try {
                final byte[] hello = channelOn(candidate, code.key, true).receive();
                if (java.util.Arrays.equals(hello, HELLO)) {
                    candidate.setSoTimeout(READ_TIMEOUT_MS);
                    return candidate;
                }
            } catch (final IOException e) {
                Log.d(Config.LOGTAG, "transfer: a connection without the key");
            }
            close(candidate);
        }
        if (!closing) {
            fail(R.string.velo_transfer_failed_old);
        }
        return null;
    }

    /** The channel keeps its count: only the frame after the hello must use a new one. */
    private final Map<Socket, VeloTransfer.Channel> channels = new HashMap<>();

    private synchronized VeloTransfer.Channel channelOn(
            final Socket on, final byte[] key, final boolean oldPhone) throws IOException {
        VeloTransfer.Channel channel = channels.get(on);
        if (channel == null) {
            channel =
                    new VeloTransfer.Channel(
                            new DataInputStream(new BufferedInputStream(on.getInputStream())),
                            new DataOutputStream(new BufferedOutputStream(on.getOutputStream())),
                            key,
                            oldPhone);
            channels.put(on, channel);
        }
        return channel;
    }

    /** The copy may carry the account as disabled: the new phone turns it on. */
    private void goOffline() {
        accountsOff = true;
        for (final Account account : xmppConnectionService.getAccounts()) {
            account.setOption(Account.OPTION_DISABLED, true);
            xmppConnectionService.reconnectAccountInBackground(account);
        }
    }

    private boolean exportBackups(final File directory) throws ExecutionException, InterruptedException {
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ExportBackupWorker.class)
                        .setInputData(
                                new Data.Builder()
                                        .putBoolean("recurring_backup", true)
                                        .putString("velo_transfer_directory", directory.getAbsolutePath())
                                        .build())
                        .build();
        final WorkManager workManager = WorkManager.getInstance(this);
        workManager.enqueue(request);
        final UUID id = request.getId();
        while (!closing) {
            final WorkInfo info = workManager.getWorkInfoById(id).get();
            if (info != null && info.getState().isFinished()) {
                return info.getState() == WorkInfo.State.SUCCEEDED;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private void sendEverything(final VeloTransfer.Channel channel, final File directory)
            throws IOException, JSONException {
        final Map<String, String> passwords = new HashMap<>();
        for (final Account account : xmppConnectionService.getAccounts()) {
            passwords.put(account.getJid().asBareJid().toString(), account.getPassword());
        }
        final List<Item> items = new ArrayList<>();
        final File[] backups = directory.listFiles();
        if (backups != null) {
            for (final File backup : backups) {
                items.add(new Item(ROOT_BACKUP, backup.getName(), backup));
            }
        }
        collect(new File(getFilesDir(), "media"), "", ROOT_INTERNAL, items);
        collect(documents(), "", ROOT_DOCUMENTS, items);
        long total = 0;
        for (final java.util.Iterator<Item> iterator = items.iterator(); iterator.hasNext(); ) {
            final Item item = iterator.next();
            if (!item.file.canRead()) {
                // In Documents, left by an earlier install: Android doesn't let this one read it
                iterator.remove();
            } else {
                total += item.file.length();
            }
        }
        long done = 0;
        final byte[] buffer = new byte[VeloTransfer.CHUNK + 1];
        for (final Item item : items) {
            final JSONObject start = new JSONObject();
            start.put("root", item.root);
            start.put("path", item.path);
            start.put("size", item.file.length());
            if (ROOT_BACKUP.equals(item.root)) {
                // Named <account>.<date>.ceb
                final String name = item.path;
                for (final Map.Entry<String, String> entry : passwords.entrySet()) {
                    if (name.startsWith(entry.getKey() + ".")) {
                        start.put("password", entry.getValue());
                    }
                }
            }
            channel.send(typed(TYPE_START, start.toString().getBytes(StandardCharsets.UTF_8)));
            try (final FileInputStream input = new FileInputStream(item.file)) {
                int read;
                while ((read = input.read(buffer, 1, VeloTransfer.CHUNK)) > 0) {
                    buffer[0] = TYPE_DATA;
                    channel.send(buffer, read + 1);
                    done += read;
                    progress(done, total);
                }
            }
            channel.send(new byte[] {TYPE_END});
        }
        channel.send(new byte[] {TYPE_ALL_DONE});
    }

    private static final class Item {
        final String root;
        final String path;
        final File file;

        Item(final String root, final String path, final File file) {
            this.root = root;
            this.path = path;
            this.file = file;
        }
    }

    private void collect(final File folder, final String prefix, final String root, final List<Item> items) {
        final File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (final File child : children) {
            if (child.isDirectory()) {
                if (!(prefix.isEmpty() && "Backup".equals(child.getName()))) {
                    collect(child, prefix + child.getName() + "/", root, items);
                }
            } else if (!".nomedia".equals(child.getName())) {
                items.add(new Item(root, prefix + child.getName(), child));
            }
        }
    }

    private static File documents() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS + "/Velo");
    }

    // ---- the new phone

    private void receiveFrom(final VeloTransfer.Code code) {
        File directory = null;
        try {
            status(R.string.velo_transfer_connecting);
            final Socket connected = new Socket();
            socket = connected;
            connected.connect(new InetSocketAddress(code.host, code.port), CONNECT_TIMEOUT_MS);
            connected.setSoTimeout(READ_TIMEOUT_MS);
            final VeloTransfer.Channel channel = channelOn(connected, code.key, false);
            channel.send(HELLO);
            status(R.string.velo_transfer_preparing);
            directory = new File(getCacheDir(), "velo-transfer");
            deleteRecursively(directory);
            if (!directory.mkdirs()) {
                throw new IOException("no temporary folder");
            }
            final List<String[]> backups = receiveEverything(channel, directory);
            status(R.string.velo_transfer_restoring);
            for (final String[] backup : backups) {
                if (!importBackup(new File(backup[0]), backup[1])) {
                    channel.send(FAILED);
                    fail(R.string.velo_transfer_failed_new);
                    return;
                }
            }
            enableAccounts();
            channel.send(DONE);
            deleteRecursively(directory);
            directory = null;
            runOnUiThread(this::showFinished);
        } catch (final IOException | InterruptedException | ExecutionException | JSONException e) {
            if (!closing) {
                Log.w(Config.LOGTAG, "transfer: receiving failed", e);
                fail(R.string.velo_transfer_failed_new);
            }
        } finally {
            if (directory != null) {
                deleteRecursively(directory);
            }
            close(socket);
        }
    }

    private List<String[]> receiveEverything(final VeloTransfer.Channel channel, final File directory)
            throws IOException, JSONException {
        final List<String[]> backups = new ArrayList<>();
        OutputStream output = null;
        File current = null;
        long done = 0;
        try {
            while (true) {
                final byte[] frame = channel.receive();
                if (frame.length == 0) {
                    throw new IOException("empty frame");
                }
                switch (frame[0]) {
                    case TYPE_START -> {
                        final JSONObject start =
                                new JSONObject(new String(frame, 1, frame.length - 1, StandardCharsets.UTF_8));
                        final String root = start.getString("root");
                        final String path = start.getString("path");
                        current = destination(directory, root, path);
                        if (current == null) {
                            throw new IOException("a file outside its folder");
                        }
                        final File parent = current.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                            Log.w(Config.LOGTAG, "transfer: a folder could not be created");
                        }
                        if (ROOT_BACKUP.equals(root)) {
                            output = new FileOutputStream(current);
                            backups.add(new String[] {current.getAbsolutePath(), start.getString("password")});
                        } else {
                            output = openMedia(current);
                        }
                    }
                    case TYPE_DATA -> {
                        if (output == null) {
                            throw new IOException("data without a file");
                        }
                        output.write(frame, 1, frame.length - 1);
                        done += frame.length - 1;
                        progressBytes(done);
                    }
                    case TYPE_END -> {
                        if (output == null) {
                            throw new IOException("end without a file");
                        }
                        output.close();
                        output = null;
                    }
                    case TYPE_ALL_DONE -> {
                        if (output != null) {
                            throw new IOException("the last file is not complete");
                        }
                        return backups;
                    }
                    default -> throw new IOException("unknown frame");
                }
            }
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (final IOException ignored) {
                }
            }
        }
    }

    /** A photo that can't be written here must not stop the chats: its bytes go nowhere. */
    private OutputStream openMedia(final File file) {
        try {
            return new FileOutputStream(file);
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "transfer: a file could not be written");
            return new OutputStream() {
                @Override
                public void write(final int b) {}
            };
        }
    }

    private File destination(final File directory, final String root, final String path) throws IOException {
        return switch (root) {
            case ROOT_BACKUP -> VeloTransfer.safeChild(directory, path);
            case ROOT_INTERNAL -> VeloTransfer.safeChild(new File(getFilesDir(), "media"), path);
            case ROOT_DOCUMENTS -> VeloTransfer.safeChild(documents(), path);
            default -> null;
        };
    }

    /** The old phone was already offline when the copy was made: here the account starts on. */
    private void enableAccounts() {
        final DatabaseBackend database = DatabaseBackend.getInstance(this);
        for (final Account account : database.getAccounts()) {
            account.setOption(Account.OPTION_DISABLED, false);
            database.updateAccount(account);
        }
    }

    private boolean importBackup(final File file, final String password)
            throws ExecutionException, InterruptedException {
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ImportBackupWorker.class)
                        .setInputData(ImportBackupWorker.data(password, Uri.fromFile(file), true))
                        .build();
        final WorkManager workManager = WorkManager.getInstance(this);
        workManager.enqueue(request);
        final UUID id = request.getId();
        while (!closing) {
            final WorkInfo info = workManager.getWorkInfoById(id).get();
            if (info != null && info.getState().isFinished()) {
                return info.getState() == WorkInfo.State.SUCCEEDED;
            }
            Thread.sleep(500);
        }
        return false;
    }

    // ---- screens

    private void showCode(final String code) {
        body.removeAllViews();
        addText(getString(R.string.velo_transfer_scan_this), 16);
        final int size = Math.min(getResources().getDisplayMetrics().widthPixels, 900) * 3 / 4;
        final Bitmap bitmap = BarcodeProvider.create2dBarcodeBitmap(code, size, 0xFF000000, 0xFFFFFFFF);
        if (bitmap != null) {
            final ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(16);
            params.bottomMargin = dp(16);
            body.addView(image, params);
        }
        addText(getString(R.string.velo_transfer_or_type), 14);
        final TextView text = addText(code, 13);
        text.setTextIsSelectable(true);
        text.setContentDescription("velo_transfer_code");
    }

    private void showCodeEntry() {
        body.removeAllViews();
        addText(getString(R.string.velo_transfer_receive_explanation), 16);
        final Button scan = addButton(R.string.velo_transfer_scan);
        scan.setOnClickListener(v -> ScanActivity.scan(this));
        addText(getString(R.string.velo_transfer_or_type), 14);
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setContentDescription("velo_transfer_field");
        body.addView(field, new LinearLayout.LayoutParams(-1, -2));
        final Button go = addButton(R.string.velo_transfer_start);
        go.setOnClickListener(v -> startReceivingFrom(field.getText().toString()));
    }

    private void startReceivingFrom(final String text) {
        final VeloTransfer.Code code = VeloTransfer.parse(text);
        if (code == null) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.velo_transfer_bad_code)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        new Thread(() -> receiveFrom(code), "velo-transfer-receive").start();
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == ScanActivity.REQUEST_SCAN_QR_CODE && resultCode == RESULT_OK && intent != null) {
            startReceivingFrom(intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT));
        }
    }

    private void showFinished() {
        body.removeAllViews();
        addText(getString(R.string.velo_transfer_finished), 16);
        final Button open = addButton(R.string.velo_transfer_open);
        open.setOnClickListener(
                v -> {
                    final Intent intent = new Intent(this, ConversationsActivity.class);
                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                    // The service of this process still has no accounts: a new process reads them
                    android.os.Process.killProcess(android.os.Process.myPid());
                });
    }

    private void status(final int text) {
        runOnUiThread(
                () -> {
                    body.removeAllViews();
                    final ProgressBar bar = new ProgressBar(this);
                    final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                    params.gravity = Gravity.CENTER_HORIZONTAL;
                    params.bottomMargin = dp(16);
                    body.addView(bar, params);
                    addText(getString(text), 16).setGravity(Gravity.CENTER);
                });
    }

    private long lastProgress = 0;

    private void progress(final long done, final long total) {
        final long now = System.currentTimeMillis();
        if (now - lastProgress < 500) {
            return;
        }
        lastProgress = now;
        final int percent = total == 0 ? 100 : (int) (done * 100 / total);
        runOnUiThread(() -> showProgress(getString(R.string.velo_transfer_sending, percent)));
    }

    private void progressBytes(final long done) {
        final long now = System.currentTimeMillis();
        if (now - lastProgress < 500) {
            return;
        }
        lastProgress = now;
        final int megabytes = (int) (done / (1024 * 1024));
        runOnUiThread(() -> showProgress(getString(R.string.velo_transfer_receiving, megabytes)));
    }

    private void showProgress(final String text) {
        if (closing) {
            return;
        }
        body.removeAllViews();
        addText(text, 16).setGravity(Gravity.CENTER);
    }

    private void fail(final int text) {
        runOnUiThread(
                () -> {
                    if (closing) {
                        return;
                    }
                    body.removeAllViews();
                    addText(getString(text), 16);
                });
    }

    private TextView addText(final String text, final int size) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        body.addView(view, params);
        return view;
    }

    private Button addButton(final int text) {
        final Button button = new com.google.android.material.button.MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        body.addView(button, params);
        return button;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static byte[] typed(final byte type, final byte[] payload) {
        final byte[] frame = new byte[payload.length + 1];
        frame[0] = type;
        System.arraycopy(payload, 0, frame, 1, payload.length);
        return frame;
    }

    private static void close(final java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (final IOException ignored) {
            }
        }
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
