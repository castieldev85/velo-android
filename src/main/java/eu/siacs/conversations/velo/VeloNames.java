package eu.siacs.conversations.velo;

import android.content.Context;

import androidx.annotation.Nullable;

import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo 0.32.2: what the app shows for an address. Only the user name, never the server's domain
 * (project rule): "velo", not "velo@server". The server itself, which has no user name, is
 * "Server". Addresses still travel in full inside the app; only what is shown is cut.
 */
public final class VeloNames {

    static final String SERVER_FALLBACK = "Server";

    private VeloNames() {}

    public static String of(@Nullable final Jid jid) {
        if (jid == null) {
            return "";
        }
        final String local = jid.getLocal();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        final Context context = Conversations.getContext();
        return context == null ? SERVER_FALLBACK : context.getString(R.string.velo_server_name);
    }

    public static String of(@Nullable final Account account) {
        return account == null ? "" : of(account.getJid());
    }

    /**
     * A name chosen or saved for {@code jid}, unless it is (or holds) the address or the server's
     * domain: then the user name, or "Server".
     */
    public static String clean(@Nullable final String name, @Nullable final Jid jid) {
        if (jid == null) {
            return name == null ? "" : name;
        }
        if (name == null || name.trim().isEmpty()) {
            return of(jid);
        }
        final String domain = jid.getDomain().toString();
        final String trimmed = name.trim();
        if (trimmed.equalsIgnoreCase(domain) || trimmed.equalsIgnoreCase(jid.asBareJid().toString())) {
            return of(jid);
        }
        final int at = trimmed.toLowerCase(java.util.Locale.ROOT).indexOf("@" + domain.toLowerCase(java.util.Locale.ROOT));
        if (at > 0) {
            return trimmed.substring(0, at) + trimmed.substring(at + 1 + domain.length());
        }
        return name;
    }

    /** For text that may hold an address typed or received as a string. */
    public static String of(@Nullable final String address) {
        if (address == null || address.isEmpty()) {
            return "";
        }
        try {
            return of(Jid.of(address));
        } catch (final IllegalArgumentException e) {
            final int at = address.indexOf('@');
            return at > 0 ? address.substring(0, at) : address;
        }
    }
}
