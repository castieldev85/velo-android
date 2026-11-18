package eu.siacs.conversations.velo;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.security.SecureRandom;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.XmppActivity;

/**
 * Velo 0.31: everyone gives their own account a strong password, without the administrator. The
 * app proposes one, shows it once to be saved, and accepts another only if it is strong enough.
 */
public class VeloPasswordActivity extends XmppActivity
        implements XmppConnectionService.OnAccountPasswordChanged {

    private static final String NOTICE_DONE = "velo_password_notice_done";
    private static final String NOTICE_NEXT = "velo_password_notice_next";
    private static final long ASK_AGAIN_MS = 3 * 24 * 60 * 60 * 1000L;
    private static final int REQUEST_UNLOCK = 0x5711;
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 20;
    public static final double MIN_STRENGTH = 64;
    private static boolean noticeOpen = false;

    /** Velo 0.42.2: whoever joins with an invite picks a strong password, so no notice for them. */
    public static void noticeNotNeeded(final XmppConnectionService service) {
        service.getPreferences().edit().putBoolean(NOTICE_DONE, true).apply();
    }

    private LinearLayout body;
    private Account account;
    private String proposed;
    private boolean unlockAsked = false;
    private boolean unlocked = false;
    private EditText own;
    private Button change;

    /** From the home, once: "later" asks again a few days after. */
    public static void maybeShowNotice(final Activity activity, final XmppConnectionService service) {
        final Account account = VeloUpdater.account(service);
        if (account == null || !account.isOnlineAndConnected() || noticeOpen || activity.isFinishing()
                || VeloWhatsNew.isOpen()) {
            return;
        }
        final SharedPreferences preferences = service.getPreferences();
        if (preferences.getBoolean(NOTICE_DONE, false)
                || System.currentTimeMillis() < preferences.getLong(NOTICE_NEXT, 0)) {
            return;
        }
        noticeOpen = true;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_password_notice_title)
                .setMessage(R.string.velo_password_notice_text)
                .setPositiveButton(
                        R.string.velo_password_notice_now,
                        (d, w) -> activity.startActivity(new Intent(activity, VeloPasswordActivity.class)))
                .setNegativeButton(
                        R.string.velo_password_notice_later,
                        (d, w) ->
                                preferences
                                        .edit()
                                        .putLong(NOTICE_NEXT, System.currentTimeMillis() + ASK_AGAIN_MS)
                                        .apply())
                .setOnDismissListener(d -> noticeOpen = false)
                .show();
    }

    /** The same measure as the server (ejabberd_auth:entropy), so what passes here passes there. */
    public static double strength(final String password) {
        int lower = 0, digit = 0, upper = 0, other = 0, high = 0;
        final byte[] bytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (final byte b : bytes) {
            final int c = b & 0xFF;
            if (c >= 'a' && c <= 'z') {
                lower = 26;
            } else if (c >= '0' && c <= '9') {
                digit = 9;
            } else if (c >= 'A' && c <= 'Z') {
                upper = 26;
            } else if (c >= 33 && c <= 126) {
                other = 33;
            } else {
                high = 128;
            }
        }
        final int pool = lower + digit + upper + other + high;
        return bytes.length == 0 || pool == 0 ? 0 : bytes.length * Math.log(pool) / Math.log(2);
    }

    static String generate() {
        final SecureRandom random = new SecureRandom();
        final StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_velo_diagnostics);
        final com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.velo_diagnostics_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        Activities.setStatusAndNavigationBarColors(this, findViewById(R.id.velo_diagnostics_root));
        toolbar.setTitle(R.string.velo_password_title);
        body = findViewById(R.id.velo_diagnostics_body);
        proposed = generate();
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        account = VeloUpdater.account(xmppConnectionService);
        if (account == null) {
            finish();
            return;
        }
        if (unlockAsked) {
            return;
        }
        unlockAsked = true;
        final KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        final Intent credentials =
                keyguard == null
                        ? null
                        : keyguard.createConfirmDeviceCredentialIntent(
                                getString(R.string.velo_password_title),
                                getString(R.string.velo_password_unlock));
        if (credentials == null) {
            unlocked = true;
            render();
        } else {
            startActivityForResult(credentials, REQUEST_UNLOCK);
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNLOCK) {
            if (resultCode == RESULT_OK) {
                unlocked = true;
                render();
            } else {
                finish();
            }
        }
    }

    private void render() {
        if (!unlocked || account == null) {
            return;
        }
        body.removeAllViews();
        addText(getString(R.string.velo_password_explanation), 16);
        final TextView shown = addText(proposed, 20);
        shown.setTypeface(Typeface.MONOSPACE);
        shown.setGravity(Gravity.CENTER);
        shown.setContentDescription("velo_password_proposed");
        addButton(R.string.velo_password_copy).setOnClickListener(v -> copy());
        addText(getString(R.string.velo_password_or_own), 14);
        own = new EditText(this);
        own.setHint(R.string.velo_password_own_hint);
        own.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        own.setContentDescription("velo_password_own");
        body.addView(own, new LinearLayout.LayoutParams(-1, -2));
        final CheckBox saved = new CheckBox(this);
        saved.setText(R.string.velo_password_saved);
        saved.setContentDescription("velo_password_saved");
        body.addView(saved, new LinearLayout.LayoutParams(-1, -2));
        change = addButton(R.string.velo_password_change);
        change.setEnabled(false);
        saved.setOnCheckedChangeListener((button, checked) -> change.setEnabled(checked));
        change.setOnClickListener(v -> submit());
    }

    private void copy() {
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            final ClipData clip = ClipData.newPlainText("", proposed);
            final PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.velo_password_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void submit() {
        final String typed = own.getText().toString();
        final String password = typed.isEmpty() ? proposed : typed;
        if (strength(password) < MIN_STRENGTH) {
            own.setError(getString(R.string.velo_password_weak));
            return;
        }
        if (!account.isOnlineAndConnected()) {
            Toast.makeText(this, R.string.velo_password_offline, Toast.LENGTH_LONG).show();
            return;
        }
        change.setEnabled(false);
        change.setText(R.string.velo_password_changing);
        xmppConnectionService.updateAccountPasswordOnServer(account, password, this);
    }

    @Override
    public void onPasswordChangeSucceeded() {
        runOnUiThread(
                () -> {
                    if (xmppConnectionService != null) {
                        xmppConnectionService.getPreferences().edit().putBoolean(NOTICE_DONE, true).apply();
                    }
                    Toast.makeText(this, R.string.velo_password_done, Toast.LENGTH_LONG).show();
                    finish();
                });
    }

    @Override
    public void onPasswordChangeFailed() {
        runOnUiThread(
                () -> {
                    if (change != null) {
                        change.setEnabled(true);
                        change.setText(R.string.velo_password_change);
                    }
                    Toast.makeText(this, R.string.velo_password_failed, Toast.LENGTH_LONG).show();
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
}
