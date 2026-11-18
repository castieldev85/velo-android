package eu.siacs.conversations.velo;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;

/**
 * Velo: what the Tor icon in the top bar shows. It looks at the profile as well as at Tor: Tor can
 * be ready while the chat is disconnected, and green has to mean that messages really go through.
 */
public enum VeloTorStatus {
    CONNECTED(0xFF2E9E4F, R.string.velo_tor_connected),
    CONNECTING(0xFFE09000, R.string.velo_tor_connecting),
    PROBLEM(0xFFD93025, R.string.velo_tor_problem);

    public final int colour;
    public final int description;

    VeloTorStatus(final int colour, final int description) {
        this.colour = colour;
        this.description = description;
    }

    /** With an account, the status of that profile; without, of all the enabled ones. */
    public static VeloTorStatus of(
            final Context context,
            @Nullable final XmppConnectionService service,
            @Nullable final Account account) {
        if (VeloTor.isDown()) {
            return PROBLEM;
        }
        final boolean torReady = VeloTor.isReady(context);
        if (service == null) {
            return CONNECTING;
        }
        final Collection<Account> accounts;
        if (account != null) {
            accounts = Collections.singletonList(account);
        } else {
            final List<Account> all = service.getAccounts();
            accounts = all == null ? Collections.emptyList() : all;
        }
        boolean anyEnabled = false;
        boolean allOnline = true;
        for (final Account item : accounts) {
            if (!item.isEnabled()) {
                continue;
            }
            anyEnabled = true;
            final Account.State state = item.getStatus();
            if (state.isError() || state == Account.State.NO_INTERNET) {
                return PROBLEM;
            }
            if (state != Account.State.ONLINE) {
                allOnline = false;
            }
        }
        return torReady && anyEnabled && allOnline ? CONNECTED : CONNECTING;
    }
}
