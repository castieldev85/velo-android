package eu.siacs.conversations.velo;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo: adding each other is automatic. A contact request is accepted and returned without
 * asking, so both sides see each other's presence and OMEMO keys right away.
 *
 * <p>Velo only talks to the account's own server: contacts and group chats on other servers can't
 * be added, and their contact requests are ignored.
 */
public final class VeloContacts {

    private VeloContacts() {}

    public static boolean autoAccept(final Account account, final Contact contact) {
        final var jid = contact.getJid();
        return jid.getLocal() != null
                && !contact.isBlocked()
                && isOwnServer(account.getJid(), jid);
    }

    /** Whether one of the contact's online devices is a Velo (caps node of PresenceGenerator). */
    public static boolean runsVelo(final Contact contact) {
        for (final var presence : contact.getPresences().getPresences()) {
            if ("urn:velo:client".equals(presence.getNode())) {
                return true;
            }
        }
        return false;
    }

    /** The account's server or one of its services (conference., upload., ...). */
    public static boolean isOwnServer(final Jid account, final Jid other) {
        final String own = account.getDomain().toString();
        final String domain = other.getDomain().toString();
        return domain.equalsIgnoreCase(own) || domain.toLowerCase().endsWith("." + own.toLowerCase());
    }

    /** The account's server itself, whose chat offers the ad-hoc commands (XEP-0133 for admins). */
    public static boolean isServerItself(final Jid account, final Jid other) {
        return account != null
                && other.isDomainJid()
                && other.getDomain().toString().equalsIgnoreCase(account.getDomain().toString());
    }

    /**
     * An address typed in the new chat dialog. A bare name is a user of the account's server, the
     * server's own name is the server itself (not a user named after it).
     */
    public static Jid typedJid(final Jid account, final String typed) {
        final String own = account.getDomain().toString();
        if (typed.indexOf('@') < 0 && !typed.equalsIgnoreCase(own)) {
            return Jid.ofLocalAndDomain(typed, own);
        }
        return Jid.of(typed);
    }
}
