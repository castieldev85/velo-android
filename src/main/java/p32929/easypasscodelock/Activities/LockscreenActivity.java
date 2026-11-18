package p32929.easypasscodelock.Activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import eu.siacs.conversations.ui.util.BackHandler;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileHelper;
import p32929.easypasscodelock.Interfaces.ActivityChanger;
import eu.siacs.conversations.R;
import p32929.easypasscodelock.Utils.EasyLock;
import p32929.easypasscodelock.Utils.EasylockSP;
import p32929.easypasscodelock.Utils.LockscreenHandler;

public class LockscreenActivity extends LockscreenHandler implements ActivityChanger {

    @SuppressWarnings("rawtypes")
    public static Class activityClassToGo;
    private final int[] passButtonIds = {
            R.id.lbtn1,
            R.id.lbtn2,
            R.id.lbtn3,
            R.id.lbtn4,
            R.id.lbtn5,
            R.id.lbtn6,
            R.id.lbtn7,
            R.id.lbtn8,
            R.id.lbtn9,
            R.id.lbtn0
    };

    private final String checkStatus = "check";
    private final String setStatus = "set";
    private final String setStatus1 = "set1";
    private final String changeStatus = "change";
    private final String changeStatus1 = "change1";
    private final String changeStatus2 = "change2";
    // Velo: setting the panic PIN
    private final String setPanicStatus = "setPanic";
    private final String setPanicStatus1 = "setPanic1";

    private char[] tempPass = null;
    private TextView textViewDot;
    private TextView textViewHAHA;
    private char[] passBuffer = new char[8];
    private int passBufferLength = 0;
    private String status = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Predictive back never calls onBackPressed() overrides on API 33+. Without this, a back
        // gesture on the PIN prompt just finished it instead of closing the app.
        BackHandler.register(this, this::handleBack);
        setContentView(R.layout.activity_easy_lockscreen);
        veloPaint();

        EasylockSP.init(this);
        initViews();

        status = getIntent().getExtras().getString("passStatus", "check");
        if (status.equals(setStatus)) {
            textViewHAHA.setText(R.string.enter_a_new_password_txt);
        } else if (status.equals(setPanicStatus)) {
            textViewHAHA.setText(R.string.velo_panic_enter);
        }

        String disableStatus = "disable";
        if (status.equals(disableStatus)) {
            EasylockSP.put("password", (String) null);
            // Velo: no unlock PIN, no panic PIN either
            EasylockSP.put(EasylockSP.PANIC_PASSWORD, (String) null);
            eu.siacs.conversations.velo.VeloAutoWipe.setDays(this, 0);
            Toast.makeText(this, getString(R.string.password_disabled_txt), Toast.LENGTH_SHORT).show();
            gotoActivity();
        }
    }

    // Velo 0.28: the unlock screen wears the colour of the top bar
    private void veloPaint() {
        final int bar = eu.siacs.conversations.velo.VeloUi.barColor(this);
        final int text = eu.siacs.conversations.velo.VeloThemes.onColor(bar);
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bar);
        final android.view.View root = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        root.setBackgroundColor(bar);
        veloPaint(root, text);
    }

    private void veloPaint(final android.view.View view, final int text) {
        if (view instanceof com.google.android.material.button.MaterialButton button) {
            button.setTextColor(text);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(text, 0x2E)));
        } else if (view instanceof TextView textView) {
            textView.setTextColor(text);
        } else if (view instanceof ImageButton imageButton) {
            imageButton.setColorFilter(text);
        } else if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                veloPaint(group.getChildAt(i), text);
            }
        }
    }

    private void initViews() {
        textViewHAHA = findViewById(R.id.span_text);
        textViewDot = findViewById(R.id.dotText);
        TextView textViewForgotPassword = findViewById(R.id.forgot_pass_textview);
        ImageButton buttonEnter = findViewById(R.id.lbtnEnter);
        ImageButton imageButtonDelete = findViewById(R.id.lbtnDelete);

        textViewForgotPassword.setOnClickListener(EasyLock.onClickListener);

        imageButtonDelete.setOnClickListener(view -> {
            if (passBufferLength > 0) {
                passBufferLength--;
                passBuffer[passBufferLength] = '\0';
            }
            updateDotText();
        });

        buttonEnter.setOnClickListener(view -> {
            final char[] currentPass = new char[passBufferLength];
            System.arraycopy(passBuffer, 0, currentPass, 0, passBufferLength);

            if (status.equals(checkStatus)) {
                // Velo: the panic PIN wipes Velo (and the peers' copies of the chats) in silence
                final String panicStr = EasylockSP.getString(EasylockSP.PANIC_PASSWORD, null);
                final char[] panic = panicStr == null ? null : panicStr.toCharArray();
                if (currentPass.length > 0 && panic != null && CryptoHelper.isEqual(currentPass, panic)) {
                    FileHelper.zero(panic);
                    FileHelper.zero(currentPass);
                    clearPassBuffer();
                    setKeypadEnabled(false);
                    eu.siacs.conversations.velo.VeloPanic.trigger(this);
                    return;
                }
                FileHelper.zero(panic);
                // Velo 0.31: after a few wrong PINs each try waits longer (the panic PIN above never does)
                final long waitMs = eu.siacs.conversations.velo.VeloPinGuard.remainingMs(this);
                if (waitMs > 0) {
                    FileHelper.zero(currentPass);
                    clearPassBuffer();
                    Toast.makeText(this, getString(R.string.velo_pin_wait, (int) (waitMs / 1000) + 1), Toast.LENGTH_LONG).show();
                    return;
                }
                final String savedPassStr = EasylockSP.getString("password", null);
                final char[] savedPass = savedPassStr == null ? null : savedPassStr.toCharArray();
                if (currentPass.length > 0 && CryptoHelper.isEqual(currentPass, savedPass)) {
                    FileHelper.zero(savedPass);
                    FileHelper.zero(currentPass);
                    eu.siacs.conversations.velo.VeloPinGuard.reset(this);
                    eu.siacs.conversations.velo.VeloAutoWipe.onUnlocked(this);
                    eu.siacs.conversations.Conversations.notifyAppUnlocked();
                    finish();
                    return;
                } else {
                    if (currentPass.length > 0) {
                        eu.siacs.conversations.velo.VeloPinGuard.failed(this);
                    }
                    clearPassBuffer();
                    Toast.makeText(this, getString(R.string.incorrect_password_txt), Toast.LENGTH_SHORT).show();
                }
                FileHelper.zero(savedPass);
            } else if (status.equals(setStatus)) {
                if (currentPass.length == 0) {
                    Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                } else if (currentPass.length < eu.siacs.conversations.velo.VeloPinGuard.MIN_LENGTH) {
                    clearPassBuffer();
                    Toast.makeText(this, R.string.velo_pin_too_short, Toast.LENGTH_LONG).show();
                } else {
                    tempPass = currentPass;
                    clearPassBuffer();
                    status = setStatus1;
                    textViewHAHA.setText(R.string.confirm_password_txt);
                    return; // Preserve currentPass in tempPass
                }
            } else if (status.equals(setStatus1)) {
                if (CryptoHelper.isEqual(currentPass, tempPass)) {
                    EasylockSP.put("password", new String(currentPass));
                    Toast.makeText(this, getString(R.string.password_is_set_txt), Toast.LENGTH_SHORT).show();
                    FileHelper.zero(tempPass);
                    tempPass = null;
                    FileHelper.zero(currentPass);
                    offerAutoWipe();
                    return;
                } else {
                    FileHelper.zero(tempPass);
                    tempPass = null;
                    clearPassBuffer();
                    status = setStatus;
                    textViewHAHA.setText(R.string.enter_a_new_password_txt);
                    Toast.makeText(this, getString(R.string.please_enter_a_new_password_again_txt), Toast.LENGTH_SHORT).show();
                }
            } else if (status.equals(setPanicStatus)) {
                final String savedPassStr = EasylockSP.getString("password", null);
                final char[] savedPass = savedPassStr == null ? null : savedPassStr.toCharArray();
                if (currentPass.length == 0) {
                    Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                } else if (currentPass.length < eu.siacs.conversations.velo.VeloPinGuard.MIN_LENGTH) {
                    clearPassBuffer();
                    Toast.makeText(this, R.string.velo_pin_too_short, Toast.LENGTH_LONG).show();
                } else if (CryptoHelper.isEqual(currentPass, savedPass)) {
                    FileHelper.zero(savedPass);
                    clearPassBuffer();
                    Toast.makeText(this, getString(R.string.velo_panic_same_as_pin), Toast.LENGTH_LONG).show();
                } else {
                    FileHelper.zero(savedPass);
                    tempPass = currentPass;
                    clearPassBuffer();
                    status = setPanicStatus1;
                    textViewHAHA.setText(R.string.confirm_password_txt);
                    return; // Preserve currentPass in tempPass
                }
                FileHelper.zero(savedPass);
            } else if (status.equals(setPanicStatus1)) {
                if (CryptoHelper.isEqual(currentPass, tempPass)) {
                    EasylockSP.put(EasylockSP.PANIC_PASSWORD, new String(currentPass));
                    Toast.makeText(this, getString(R.string.velo_panic_set), Toast.LENGTH_LONG).show();
                    FileHelper.zero(tempPass);
                    tempPass = null;
                    FileHelper.zero(currentPass);
                    finish();
                    return;
                } else {
                    FileHelper.zero(tempPass);
                    tempPass = null;
                    clearPassBuffer();
                    status = setPanicStatus;
                    textViewHAHA.setText(R.string.velo_panic_enter);
                    Toast.makeText(this, getString(R.string.please_enter_a_new_password_again_txt), Toast.LENGTH_SHORT).show();
                }
            } else if (status.equals(changeStatus)) {
                final String savedPassStr = EasylockSP.getString("password", null);
                final char[] savedPass = savedPassStr == null ? null : savedPassStr.toCharArray();
                if (CryptoHelper.isEqual(currentPass, savedPass)) {
                    clearPassBuffer();
                    status = changeStatus1;
                    textViewHAHA.setText(R.string.enter_a_new_password_txt);
                } else {
                    clearPassBuffer();
                    Toast.makeText(this, getString(R.string.please_enter_current_password_txt), Toast.LENGTH_SHORT).show();
                }
                FileHelper.zero(savedPass);
            } else if (status.equals(changeStatus1)) {
                if (currentPass.length == 0) {
                    Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                } else if (currentPass.length < eu.siacs.conversations.velo.VeloPinGuard.MIN_LENGTH) {
                    clearPassBuffer();
                    Toast.makeText(this, R.string.velo_pin_too_short, Toast.LENGTH_LONG).show();
                } else {
                    tempPass = currentPass;
                    clearPassBuffer();
                    status = changeStatus2;
                    textViewHAHA.setText(R.string.confirm_password_txt);
                    return; // Preserve currentPass in tempPass
                }
            } else if (status.equals(changeStatus2)) {
                if (CryptoHelper.isEqual(currentPass, tempPass)) {
                    EasylockSP.put("password", new String(currentPass));
                    Toast.makeText(this, getString(R.string.password_changed_txt), Toast.LENGTH_SHORT).show();
                    FileHelper.zero(tempPass);
                    tempPass = null;
                    gotoActivity();
                    FileHelper.zero(currentPass);
                    return;
                } else {
                    FileHelper.zero(tempPass);
                    tempPass = null;
                    clearPassBuffer();
                    status = changeStatus1;
                    textViewHAHA.setText(R.string.enter_a_new_password_txt);
                    Toast.makeText(this, getString(R.string.please_enter_a_new_password_again_txt), Toast.LENGTH_SHORT).show();
                }
            }

            FileHelper.zero(currentPass);
        });

        for (int passButtonId : passButtonIds) {
            final Button button = findViewById(passButtonId);
            button.setOnClickListener(v -> {
                if (passBufferLength >= 8) {
                    Toast.makeText(LockscreenActivity.this, getString(R.string.max_8_characters_txt), Toast.LENGTH_SHORT).show();
                } else {
                    passBuffer[passBufferLength] = button.getText().charAt(0);
                    passBufferLength++;
                }
                updateDotText();
            });
        }
    }

    // Velo 0.31: a new PIN is the moment to offer the wipe of a phone left alone
    private void offerAutoWipe() {
        if (eu.siacs.conversations.velo.VeloAutoWipe.days(this) > 0) {
            gotoActivity();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.velo_autowipe_offer_title)
                .setMessage(R.string.velo_autowipe_offer_text)
                .setCancelable(false)
                .setPositiveButton(R.string.velo_autowipe_offer_yes, (d, w) -> {
                    eu.siacs.conversations.velo.VeloAutoWipe.setDays(this, 14);
                    gotoActivity();
                })
                .setNegativeButton(R.string.velo_autowipe_offer_no, (d, w) -> gotoActivity())
                .show();
    }

    private void updateDotText() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < passBufferLength; i++) {
            builder.append("*");
        }
        textViewDot.setText(builder.toString());
    }

    // Velo: while the panic wipe runs the screen stays as it is, only the keys stop answering
    private void setKeypadEnabled(final boolean enabled) {
        for (final int id : passButtonIds) {
            findViewById(id).setEnabled(enabled);
        }
        findViewById(R.id.lbtnEnter).setEnabled(enabled);
        findViewById(R.id.lbtnDelete).setEnabled(enabled);
    }

    private void clearPassBuffer() {
        FileHelper.zero(passBuffer);
        passBufferLength = 0;
        updateDotText();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearPassBuffer();
        FileHelper.zero(tempPass);
    }

    private void gotoActivity() {
        if (activityClassToGo != null) {
            Intent intent = new Intent(LockscreenActivity.this, activityClassToGo);
            startActivity(intent);
        }
        finish();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void activityClass(Class activityClass) {
        activityClassToGo = activityClass;
    }

    private boolean handleBack() {
        if (status.equals(checkStatus)) {
            finishAffinity();
            return true;
        }
        return false;
    }
}
