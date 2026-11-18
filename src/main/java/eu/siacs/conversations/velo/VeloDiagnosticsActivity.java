package eu.siacs.conversations.velo;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.Locale;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.XmppActivity;

/** Velo 0.29: read-only state of connection, Tor and battery, on a screen you can photograph. */
public class VeloDiagnosticsActivity extends XmppActivity {

    private static final int GOOD = 0xFF2FB36E;
    private static final int BAD = 0xFFE5484D;
    private static final int WARN = 0xFFE08A1E;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // An open log stays still, so it can be selected and read
            if (!torOpen && !crashOpen) {
                render();
            }
            handler.postDelayed(this, 3000);
        }
    };
    private LinearLayout body;
    private boolean torOpen = false;
    private boolean crashOpen = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_velo_diagnostics);
        final com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.velo_diagnostics_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        Activities.setStatusAndNavigationBarColors(this, findViewById(R.id.velo_diagnostics_root));
        body = findViewById(R.id.velo_diagnostics_body);
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        render();
    }

    private void render() {
        body.removeAllViews();
        final Account account = xmppConnectionService == null ? null : VeloUpdater.account(xmppConnectionService);
        final boolean connected = account != null && account.isOnlineAndConnected();
        final VeloTorStatus status = VeloTorStatus.of(this, xmppConnectionService, null);
        addHero(connected, getString(status.description));

        final LinearLayout connection = addGroup(R.string.velo_diag_connection);
        int attempts = 0;
        if (account == null) {
            addLine(connection, R.string.velo_diag_state, getString(R.string.velo_diag_no_account), BAD);
        } else {
            addLine(connection, R.string.velo_diag_state,
                    getString(connected ? R.string.velo_diag_on : R.string.velo_diag_off), connected ? GOOD : BAD);
            final var xmpp = account.getXmppConnection();
            if (xmpp != null) {
                attempts = xmpp.getAttempt();
                final long now = SystemClock.elapsedRealtime();
                if (connected) {
                    addLine(connection, R.string.velo_diag_since, duration(xmpp.getConnectionDuration()), 0);
                }
                addLine(connection, R.string.velo_diag_tries, String.valueOf(attempts), 0);
                addLine(connection, R.string.velo_diag_last_connect, duration(xmpp.getVeloLastConnectMs()), 0);
                addLine(connection, R.string.velo_diag_ping, ago(now, xmpp.getLastPingSent()), 0);
                addLine(connection, R.string.velo_diag_packet, ago(now, xmpp.getLastPacketReceived()), 0);
            }
        }

        final LinearLayout torBattery = addGroup(R.string.velo_diag_tor_battery);
        addLine(torBattery, R.string.velo_diag_tor, VeloTor.statusText(this), VeloTor.isReady(this) ? GOOD : BAD);
        // Velo 0.38.1: tempi e circuiti di Tor, mai i nodi
        addLine(torBattery, R.string.velo_diag_tor_startup, duration(VeloTor.startupMs()), 0);
        addLine(torBattery, R.string.velo_diag_tor_ready_for, duration(VeloTor.readyForMs()), 0);
        final int circuits = VeloTor.circuits(this);
        addLine(torBattery, R.string.velo_diag_tor_circuits, circuits < 0 ? "—" : String.valueOf(circuits),
                circuits == 0 ? WARN : 0);
        final PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final boolean exempt = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        addLine(torBattery, R.string.velo_diag_battery,
                getString(exempt ? R.string.velo_diag_battery_off : R.string.velo_diag_battery_on), exempt ? GOOD : WARN);

        final int uid = getApplicationInfo().uid;
        final long received = TrafficStats.getUidRxBytes(uid);
        final long sent = TrafficStats.getUidTxBytes(uid);
        if (received > 0 || sent > 0) {
            final LinearLayout data = addGroup(R.string.velo_diag_data);
            addLine(data, R.string.velo_diag_received, bytes(received), 0);
            addLine(data, R.string.velo_diag_sent, bytes(sent), 0);
        }

        if (attempts > 3 || !exempt) {
            final TextView hint = text(getString(R.string.velo_diag_hint), 13, secondary());
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(dp(6), 0, dp(6), dp(12));
            body.addView(hint, params);
        }

        final String tor = VeloTor.torLog(this);
        if (tor != null) {
            addDetails(R.string.velo_diag_log, tor, torOpen, open -> torOpen = open);
        }
        final String crash = VeloCrashLog.last(this);
        addDetails(R.string.velo_diag_crash, crash == null ? getString(R.string.velo_diag_no_crash) : crash,
                crashOpen, open -> crashOpen = open);
    }

    private void addHero(final boolean good, final String detail) {
        final int colour = good ? GOOD : BAD;
        final LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(20));
        background.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(colour, 0x2E));
        hero.setBackground(background);
        final ImageView icon = new ImageView(this);
        icon.setImageResource(good ? R.drawable.ic_check_24dp : R.drawable.ic_warning_24dp);
        icon.setColorFilter(0xFFFFFFFF);
        final GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(colour);
        icon.setBackground(dot);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        hero.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        final LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(getString(good ? R.string.velo_diag_ok : R.string.velo_diag_ko), 18, primary());
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        words.addView(title);
        words.addView(text(detail, 14, secondary()));
        final LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        wordsParams.setMarginStart(dp(14));
        hero.addView(words, wordsParams);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        body.addView(hero, params);
    }

    private LinearLayout addGroup(final int titleRes) {
        final TextView title = text(getString(titleRes), 14, MaterialColors.getColor(body, androidx.appcompat.R.attr.colorPrimary));
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(dp(6), dp(6), dp(6), dp(8));
        body.addView(title, titleParams);
        final LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.velo_card_single);
        group.setPadding(dp(16), dp(6), dp(16), dp(6));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        body.addView(group, params);
        return group;
    }

    private void addLine(final LinearLayout group, final int labelRes, final String value, final int colour) {
        final LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(10), 0, dp(10));
        line.addView(text(getString(labelRes), 15, secondary()), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView shown = text(value, 15, colour != 0 ? colour : primary());
        shown.setTypeface(shown.getTypeface(), Typeface.BOLD);
        shown.setGravity(Gravity.END);
        final LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, -2, 1f);
        valueParams.setMarginStart(dp(12));
        line.addView(shown, valueParams);
        group.addView(line);
    }

    private interface OnToggle {
        void toggled(boolean open);
    }

    private void addDetails(final int titleRes, final String content, final boolean open, final OnToggle onToggle) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.velo_card_single);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        final TextView title = text((open ? "▾  " : "▸  ") + getString(titleRes), 15, primary());
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        box.addView(title);
        final TextView shown = text(content, 13, secondary());
        shown.setTextIsSelectable(true);
        shown.setPadding(0, dp(10), 0, 0);
        shown.setVisibility(open ? View.VISIBLE : View.GONE);
        box.addView(shown);
        title.setOnClickListener(v -> {
            onToggle.toggled(!open);
            render();
        });
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        body.addView(box, params);
    }

    private TextView text(final String value, final int size, final int colour) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(colour);
        return view;
    }

    private int primary() {
        return MaterialColors.getColor(body, com.google.android.material.R.attr.colorOnSurface);
    }

    private int secondary() {
        return MaterialColors.getColor(body, com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Nothing yet is a zero time: it must not read as "since the phone was switched on". */
    private String ago(final long now, final long when) {
        return when <= 0 || now < when ? "—" : getString(R.string.velo_diag_ago, duration(now - when));
    }

    private static String duration(final long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        final long d = s / 86400; s %= 86400;
        final long h = s / 3600; s %= 3600;
        final long m = s / 60; s %= 60;
        if (d > 0) return d + "g " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private static String bytes(final long bytes) {
        if (bytes < 0) return "—";
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ITALY, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
        if (bytes >= 1024L * 1024) return String.format(Locale.ITALY, "%.1f MB", bytes / 1024.0 / 1024);
        if (bytes >= 1024L) return String.format(Locale.ITALY, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
