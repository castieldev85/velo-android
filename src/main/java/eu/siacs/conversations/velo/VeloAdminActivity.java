package eu.siacs.conversations.velo;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo 0.32: the server's users as a list. A tap on a user offers password, block, disconnect and
 * delete; "new user" asks only the name. Passwords are always made here (the admin commands skip
 * the server's strength check) and shown once. Only the account the server treats as admin sees
 * this, and only after the phone's own lock.
 */
public class VeloAdminActivity extends XmppActivity
        implements eu.siacs.conversations.services.XmppConnectionService.OnConversationUpdate {

    private static final int REQUEST_UNLOCK = 0x5712;

    private LinearLayout body;
    private LinearLayout rows;
    private EditText search;
    private Account account;
    private boolean unlockAsked = false;
    private boolean unlocked = false;
    private boolean searching = false;
    private List<Jid> users = new ArrayList<>();
    private Set<Jid> online = new HashSet<>();
    private Set<Jid> disabled = new HashSet<>();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_velo_diagnostics);
        final com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.velo_diagnostics_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        Activities.setStatusAndNavigationBarColors(this, findViewById(R.id.velo_diagnostics_root));
        toolbar.setTitle(R.string.velo_admin_title);
        body = findViewById(R.id.velo_diagnostics_body);
    }

    /** Velo 0.41: the wipes still waiting when the list was drawn; an answer reloads it. */
    private int pendingShown = -1;

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        if (account == null || rows == null) {
            return;
        }
        int pending = 0;
        for (final RemoteWipe.Pending p : RemoteWipe.pending(this)) {
            if (p.admin.equals(account.getUuid())) {
                pending++;
            }
        }
        if (pendingShown >= 0 && pending != pendingShown) {
            pendingShown = pending;
            load();
        } else {
            pendingShown = pending;
        }
    }

    @Override
    protected void onBackendConnected() {
        if (unlockAsked) {
            return;
        }
        unlockAsked = true;
        final KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        final Intent credentials =
                keyguard == null
                        ? null
                        : keyguard.createConfirmDeviceCredentialIntent(
                                getString(R.string.velo_admin_title), getString(R.string.velo_admin_unlock));
        if (credentials == null) {
            unlocked = true;
            findAdmin();
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
                findAdmin();
            } else {
                finish();
            }
        }
    }

    /** The first connected account whose server offers it the admin commands. */
    private void findAdmin() {
        if (!unlocked || xmppConnectionService == null || searching) {
            return;
        }
        searching = true;
        showMessage(getString(R.string.velo_admin_loading));
        final List<Account> candidates = new ArrayList<>();
        for (final Account candidate : xmppConnectionService.getAccounts()) {
            if (candidate.isEnabled() && candidate.isOnlineAndConnected()) {
                candidates.add(candidate);
            }
        }
        tryAdmin(candidates, 0);
    }

    private void tryAdmin(final List<Account> candidates, final int index) {
        if (index >= candidates.size()) {
            runOnUiThread(() -> {
                searching = false;
                showMessage(getString(R.string.velo_admin_none));
            });
            return;
        }
        final Account candidate = candidates.get(index);
        VeloAdmin.checkAdmin(xmppConnectionService, candidate, admin -> {
            if (admin) {
                runOnUiThread(() -> {
                    searching = false;
                    account = candidate;
                    load();
                });
            } else {
                tryAdmin(candidates, index + 1);
            }
        });
    }

    private void load() {
        if (account == null || isFinishing()) {
            return;
        }
        showMessage(getString(R.string.velo_admin_loading));
        VeloAdmin.list(xmppConnectionService, account, VeloAdmin.LIST_USERS, "registereduserjids", all ->
                VeloAdmin.list(xmppConnectionService, account, VeloAdmin.LIST_ONLINE, "onlineuserjids", on ->
                        VeloAdmin.list(xmppConnectionService, account, VeloAdmin.LIST_DISABLED, "disableduserjids", off ->
                                runOnUiThread(() -> {
                                    if (isFinishing()) {
                                        return;
                                    }
                                    if (all == null) {
                                        showMessage(getString(R.string.velo_admin_failed));
                                        return;
                                    }
                                    users = all;
                                    online = on == null ? new HashSet<>() : new HashSet<>(on);
                                    disabled = off == null ? new HashSet<>() : new HashSet<>(off);
                                    render();
                                }))));
    }

    private void showMessage(final String text) {
        body.removeAllViews();
        rows = null;
        addText(body, text, 16);
    }

    private void render() {
        final String filter = search == null ? "" : search.getText().toString();
        body.removeAllViews();
        addText(body, getString(R.string.velo_admin_intro), 15);
        final Button add = new com.google.android.material.button.MaterialButton(this);
        add.setText(R.string.velo_admin_new_user);
        add.setAllCaps(false);
        add.setOnClickListener(v -> askNewUser());
        body.addView(add, margins(new LinearLayout.LayoutParams(-1, -2)));
        // Velo 0.39: QR code that brings a new phone to this server (never a password)
        final VeloServers.Server server = VeloServers.forAccount(account);
        if (server != null && server.onion != null && !server.pins.isEmpty()) {
            final Button invite = new com.google.android.material.button.MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            invite.setText(R.string.velo_invite_show);
            invite.setAllCaps(false);
            invite.setOnClickListener(v -> showInvite(server));
            body.addView(invite, margins(new LinearLayout.LayoutParams(-1, -2)));
        }
        search = new EditText(this);
        search.setHint(R.string.velo_admin_search);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setText(filter);
        body.addView(search, margins(new LinearLayout.LayoutParams(-1, -2)));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                renderRows();
            }
        });
        renderRows();
    }

    private void renderRows() {
        if (rows == null) {
            return;
        }
        rows.removeAllViews();
        final String filter = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (final Jid user : users) {
            final String name = user.getLocal() == null ? user.toString() : user.getLocal();
            if (!filter.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(12), dp(8), dp(12));
            final TypedValue ripple = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            final TextView title = addText(row, name, 18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            final String status = status(user);
            if (!status.isEmpty()) {
                addText(row, status, 14);
            }
            row.setContentDescription("velo_admin_user_" + name);
            row.setOnClickListener(v -> openUser(user, name));
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private String status(final Jid user) {
        final List<String> parts = new ArrayList<>();
        if (isSelf(user)) {
            parts.add(getString(R.string.velo_admin_you));
        }
        if (account != null && RemoteWipe.findPending(this, account, user) != null) {
            parts.add(getString(R.string.velo_admin_wipe_waiting));
        }
        if (disabled.contains(user)) {
            parts.add(getString(R.string.velo_admin_disabled));
        } else if (online.contains(user)) {
            parts.add(getString(R.string.velo_admin_online));
        }
        return String.join(" · ", parts);
    }

    private boolean isSelf(final Jid user) {
        return account != null && account.getJid().asBareJid().equals(user);
    }

    private void openUser(final Jid user, final String name) {
        if (isSelf(user)) {
            Toast.makeText(this, R.string.velo_admin_self_locked, Toast.LENGTH_LONG).show();
            return;
        }
        final boolean blocked = disabled.contains(user);
        final RemoteWipe.Pending pending = RemoteWipe.findPending(this, account, user);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (pending != null) {
            // Velo 0.41: the phone has not answered yet
            labels.add(getString(R.string.velo_admin_wipe_delete_now));
            actions.add(() -> confirm(getString(R.string.velo_admin_confirm_wipe_delete_now, name),
                    R.string.velo_admin_delete_forever, () -> {
                        RemoteWipe.removePending(this, account, user);
                        perform(VeloAdmin.DELETE, user, null);
                    }));
            labels.add(getString(R.string.velo_admin_wipe_stop_waiting));
            actions.add(() -> {
                RemoteWipe.removePending(this, account, user);
                renderRows();
            });
        }
        labels.add(getString(R.string.velo_admin_action_password));
        actions.add(() -> confirm(getString(R.string.velo_admin_confirm_password, name),
                R.string.velo_admin_action_password,
                () -> perform(VeloAdmin.PASSWORD, user, VeloPasswordActivity.generate())));
        labels.add(getString(blocked ? R.string.velo_admin_action_enable : R.string.velo_admin_action_disable));
        actions.add(() -> {
            if (blocked) {
                perform(VeloAdmin.ENABLE, user, null);
            } else {
                confirm(getString(R.string.velo_admin_confirm_disable, name),
                        R.string.velo_admin_action_disable, () -> perform(VeloAdmin.DISABLE, user, null));
            }
        });
        labels.add(getString(R.string.velo_admin_action_kick));
        actions.add(() -> confirm(getString(R.string.velo_admin_confirm_kick, name),
                R.string.velo_admin_action_kick, () -> perform(VeloAdmin.KICK, user, null)));
        if (pending == null && !blocked) {
            labels.add(getString(R.string.velo_admin_action_remove_wipe));
            actions.add(() -> confirm(getString(R.string.velo_admin_confirm_remove_wipe, name),
                    R.string.velo_admin_action_remove_wipe,
                    () -> wipe(user, RemoteWipe.MODE_DELETE)));
            labels.add(getString(R.string.velo_admin_action_wipe_phone));
            actions.add(() -> confirm(getString(R.string.velo_admin_confirm_wipe_phone, name),
                    R.string.velo_admin_action_wipe_phone,
                    () -> wipe(user, RemoteWipe.MODE_KEEP)));
        }
        labels.add(getString(R.string.velo_admin_action_delete));
        actions.add(() -> confirm(getString(R.string.velo_admin_confirm_delete, name),
                R.string.velo_admin_action_delete,
                () -> confirm(getString(R.string.velo_admin_confirm_delete_again, name),
                        R.string.velo_admin_delete_forever, () -> {
                            RemoteWipe.removePending(this, account, user);
                            perform(VeloAdmin.DELETE, user, null);
                        })));
        new MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Velo 0.41: the encrypted wipe goes to the user's phone; see RemoteWipe. */
    private void wipe(final Jid user, final String mode) {
        if (account == null || !account.isOnlineAndConnected()) {
            Toast.makeText(this, R.string.velo_admin_offline, Toast.LENGTH_LONG).show();
            return;
        }
        xmppConnectionService.sendVeloRemoteWipe(account, user, mode);
        Toast.makeText(this, R.string.velo_admin_wipe_sent, Toast.LENGTH_LONG).show();
        renderRows();
    }

    private void confirm(final String message, final int action, final Runnable then) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(action, (d, w) -> then.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askNewUser() {
        final EditText name = new EditText(this);
        name.setHint(R.string.velo_admin_new_name_hint);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setContentDescription("velo_admin_new_name");
        final LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.velo_admin_new_user)
                .setView(box)
                .setPositiveButton(R.string.velo_admin_create, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String clean = VeloAdmin.cleanName(name.getText().toString());
            if (clean == null) {
                name.setError(getString(R.string.velo_admin_name_invalid));
                return;
            }
            final Jid user = Jid.ofLocalAndDomain(clean, account.getDomain().toString());
            if (users.contains(user)) {
                name.setError(getString(R.string.velo_admin_name_exists));
                return;
            }
            dialog.dismiss();
            final String password = VeloPasswordActivity.generate();
            perform(VeloAdmin.ADD, user, password);
        });
    }

    /** Runs a command on one user; with a password, shows it once when the server has taken it. */
    private void perform(final String command, final Jid user, final String password) {
        if (account == null || !account.isOnlineAndConnected()) {
            Toast.makeText(this, R.string.velo_admin_offline, Toast.LENGTH_LONG).show();
            return;
        }
        showMessage(getString(R.string.velo_admin_working));
        VeloAdmin.run(xmppConnectionService, account, command, VeloAdmin.forUser(command, user, password), ok ->
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    if (!ok) {
                        Toast.makeText(this, R.string.velo_admin_failed, Toast.LENGTH_LONG).show();
                    } else if (password != null) {
                        showPassword(user.getLocal(), password);
                    } else {
                        Toast.makeText(this, R.string.velo_admin_done, Toast.LENGTH_SHORT).show();
                    }
                    load();
                }));
    }

    private void showPassword(final String name, final String password) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        addText(box, getString(R.string.velo_admin_password_text, name), 15);
        final TextView shown = addText(box, password, 20);
        shown.setTypeface(Typeface.MONOSPACE);
        shown.setGravity(Gravity.CENTER);
        shown.setTextIsSelectable(true);
        shown.setContentDescription("velo_admin_password");
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.velo_admin_password_title, name))
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(R.string.velo_admin_copy, (d, w) -> copy(password))
                .show();
    }

    private void showInvite(final VeloServers.Server server) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        addText(box, getString(R.string.velo_invite_show_text), 15);
        // The verification code goes above the QR code: below it, it did not fit in the dialog
        final TextView label = addText(box, getString(R.string.velo_invite_code_label), 14);
        label.setGravity(Gravity.CENTER);
        final TextView code = addText(box, server.shortCode(), 24);
        code.setGravity(Gravity.CENTER);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setContentDescription("velo_invite_code");
        final android.widget.ImageView qr = new android.widget.ImageView(this);
        final int size = dp(200);
        qr.setImageBitmap(eu.siacs.conversations.services.BarcodeProvider.create2dBarcodeBitmap(
                server.toInvite(), size, 0xFF000000, 0xFFFFFFFF));
        qr.setContentDescription("velo_invite_qr");
        final LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(size, size);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.topMargin = dp(8);
        box.addView(qr, qrParams);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.velo_invite_show_title, server.name))
                .setView(box)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void copy(final String password) {
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            final ClipData clip = ClipData.newPlainText("", password);
            final PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.velo_admin_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private TextView addText(final LinearLayout parent, final String text, final int size) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        parent.addView(view, margins(new LinearLayout.LayoutParams(-1, -2)));
        return view;
    }

    private LinearLayout.LayoutParams margins(final LinearLayout.LayoutParams params) {
        params.bottomMargin = dp(8);
        return params;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
