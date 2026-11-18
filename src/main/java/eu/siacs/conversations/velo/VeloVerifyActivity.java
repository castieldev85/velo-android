package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlSession;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.OmemoActivity;
import eu.siacs.conversations.ui.ScanActivity;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo: checking a contact, in a few taps. Both read the same code to each other, in person or on a
 * call, or one scans the other's QR. Each phone of the contact has its own code.
 */
public class VeloVerifyActivity extends OmemoActivity {

    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_CONTACT = "contact";

    private LinearLayout body;
    private Contact contact;

    public static void start(final Context context, final Contact contact) {
        context.startActivity(
                new Intent(context, VeloVerifyActivity.class)
                        .putExtra(EXTRA_ACCOUNT, contact.getAccount().getJid().asBareJid().toString())
                        .putExtra(EXTRA_CONTACT, contact.getJid().asBareJid().toString()));
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_velo_diagnostics);
        final com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.velo_diagnostics_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        Activities.setStatusAndNavigationBarColors(this, findViewById(R.id.velo_diagnostics_root));
        body = findViewById(R.id.velo_diagnostics_body);
        toolbar.setTitle(R.string.velo_verify_title);
    }

    @Override
    public void onBackendConnected() {
        final Account account =
                xmppConnectionService.findAccountByJid(Jid.of(getIntent().getStringExtra(EXTRA_ACCOUNT)));
        if (account == null) {
            finish();
            return;
        }
        contact = account.getRoster().getContact(Jid.of(getIntent().getStringExtra(EXTRA_CONTACT)));
        render();
    }

    @Override
    protected void refreshUiReal() {
        if (contact != null) {
            render();
        }
    }

    @Override
    protected void processFingerprintVerification(final XmppUri uri) {
        if (contact != null
                && contact.getJid().asBareJid().equals(uri.getJid())
                && uri.hasFingerprints()) {
            if (xmppConnectionService.verifyFingerprints(contact, uri.getFingerprints())) {
                Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show();
            }
            render();
        } else {
            Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show();
        }
    }

    private void render() {
        body.removeAllViews();
        final Account account = contact.getAccount();
        final AxolotlService axolotl = account.getAxolotlService();
        final String ownJid = account.getJid().asBareJid().toString();
        final String peerJid = contact.getJid().asBareJid().toString();
        addText(getString(R.string.velo_verify_explanation, contact.getDisplayName()), 16);
        final String ownKey = axolotl.getOwnHybridFingerprint();
        final List<XmppAxolotlSession> sessions = new ArrayList<>();
        for (final XmppAxolotlSession session : axolotl.findSessionsForContact(contact)) {
            if (session.getTrust().isActive()) {
                sessions.add(session);
            }
        }
        if (sessions.isEmpty()) {
            addText(getString(R.string.velo_verify_no_keys), 16);
        }
        int number = 0;
        for (final XmppAxolotlSession session : sessions) {
            number++;
            if (sessions.size() > 1) {
                addText(getString(R.string.velo_verify_phone_number, number), 14);
            }
            final String fingerprint = session.getFingerprint();
            final String peerKey = axolotl.hybridFingerprintFor(peerJid, fingerprint);
            if (ownKey == null || peerKey == null) {
                addText(getString(R.string.velo_verify_no_keys), 16);
                continue;
            }
            final TextView code = addText(VeloSafetyCode.compute(ownJid, ownKey, peerJid, peerKey), 22);
            code.setTypeface(Typeface.MONOSPACE);
            code.setGravity(Gravity.CENTER);
            code.setContentDescription("velo_safety_code");
            final FingerprintStatus status = axolotl.getFingerprintTrust(peerJid, fingerprint);
            if (status.isVerified()) {
                addText(getString(R.string.velo_verify_done), 16).setGravity(Gravity.CENTER);
            } else {
                addButton(R.string.velo_verify_match)
                        .setOnClickListener(
                                v -> {
                                    axolotl.setFingerprintTrust(peerJid, fingerprint, status.toVerified());
                                    render();
                                });
                addButton(R.string.velo_verify_mismatch)
                        .setOnClickListener(
                                v ->
                                        new MaterialAlertDialogBuilder(this)
                                                .setTitle(R.string.velo_verify_mismatch)
                                                .setMessage(R.string.velo_verify_mismatch_explanation)
                                                .setPositiveButton(R.string.ok, null)
                                                .show());
            }
        }
        addText(getString(R.string.velo_verify_or_qr), 14);
        addButton(R.string.velo_verify_scan).setOnClickListener(v -> ScanActivity.scan(this));
        addButton(R.string.velo_verify_show_qr).setOnClickListener(v -> showQrCode(account.getShareableUri()));
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
        params.bottomMargin = dp(8);
        body.addView(button, params);
        return button;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
