package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Velo 0.41: the server's admin wipes a user's phone from "Manage users".
 *
 * <p>The admin's Velo sends the user an encrypted {@code <wipe/>}; the user's Velo honours it only
 * when it comes from {@code admin@} of the account's own server, from a key it trusts, and was
 * issued after the account was added on this phone. It answers with an encrypted {@code <wiped/>},
 * then deletes everything (or only that account, when the phone holds others). The admin's Velo,
 * on the answer, deletes the account on the server when that was asked.
 *
 * <p>Same transport as {@link HistoryPurge}: inside the SCE envelope with OMEMO2; with legacy OMEMO
 * the element rides on the outer stanza and counts only when the decrypted body is the fixed
 * text, which only the sender can produce. Unencrypted commands are ignored.
 *
 * <p>The server keeps no archive (no MAM): a command waits in the offline queue until delivered,
 * then it is gone, so it cannot come back to a phone set up later.
 */
public final class RemoteWipe {

    public static final String NAMESPACE = "urn:velo:remote-wipe:0";
    public static final String WIPE = "wipe";
    public static final String WIPED = "wiped";
    /** Velo 0.50: the end of a guest account ("Ospite a tempo"), sent by the admin; see VeloGuest. */
    public static final String EXPIRES = "expires";
    public static final String EXPIRES_BODY = "Velo: accesso ospite a tempo.";
    private static final String UNTIL = "until";
    /** The admin's account on every Velo server (the "admin" acl of ejabberd). */
    public static final String ADMIN = "admin";
    public static final String WIPE_BODY = "Velo: il gestore ha scollegato questo telefono.";
    public static final String WIPED_BODY = "Velo: telefono cancellato.";
    private static final String ISSUED = "issued";
    /** "delete": the admin's Velo deletes the account on the answer; "keep": only the phone goes. */
    private static final String MODE = "mode";
    public static final String MODE_DELETE = "delete";
    public static final String MODE_KEEP = "keep";

    private static final String PREFERENCES = "velo_remote_wipe";
    private static final String PENDING = "pending";

    private RemoteWipe() {}

    /** A command or an answer read from a received message. */
    public static final class Command {
        public final String name;
        public final long issued;
        public final String mode;
        /** For {@link #EXPIRES}: when the guest account ends (ms since 1970); 0 otherwise. */
        public final long until;

        Command(final String name, final long issued, final String mode, final long until) {
            this.name = name;
            this.issued = issued;
            this.mode = mode;
            this.until = until;
        }

        public boolean isWipe() {
            return WIPE.equals(name);
        }

        public boolean isExpires() {
            return EXPIRES.equals(name);
        }
    }

    public static Element createWipe(final long issued, final String mode) {
        final Element wipe = new Element(WIPE, NAMESPACE);
        wipe.setAttribute(ISSUED, String.valueOf(issued));
        wipe.setAttribute(MODE, mode);
        return wipe;
    }

    public static Element createExpires(final long issued, final long until) {
        final Element expires = new Element(EXPIRES, NAMESPACE);
        expires.setAttribute(ISSUED, String.valueOf(issued));
        expires.setAttribute(UNTIL, String.valueOf(until));
        return expires;
    }

    public static Element createWiped(final long issued) {
        final Element wiped = new Element(WIPED, NAMESPACE);
        wiped.setAttribute(ISSUED, String.valueOf(issued));
        return wiped;
    }

    public static boolean is(final Element element) {
        return NAMESPACE.equals(element.getNamespace())
                && (WIPE.equals(element.getName()) || WIPED.equals(element.getName())
                        || EXPIRES.equals(element.getName()));
    }

    /** Whether this outgoing message is a command or an answer: sent, never kept in the chat. */
    public static boolean isControl(final Message message) {
        return find(message) != null;
    }

    /** The command in a received message, or null when it is none or not validly encrypted. */
    @Nullable
    public static Command extract(final Message message, final Element stanza) {
        final Element element;
        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            element = find(message);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
            final String body = message.getRawBody();
            // W1 (sotto-punto): con OMEMO legacy "issued"/"mode" stanno sulla stanza ESTERNA in
            // chiaro, che il server puo' manomettere. Il wipe (distruttivo) e' accettato solo via
            // OMEMO2, dove quegli attributi viaggiano cifrati e autenticati nell'envelope SCE.
            // La risposta "wiped" (non distruttiva, lato admin) resta accettata anche su legacy.
            if (WIPED_BODY.equals(body)) {
                element = stanza.findChild(WIPED, NAMESPACE);
            } else {
                element = null;
            }
        } else {
            return null;
        }
        if (element == null) {
            return null;
        }
        try {
            final long issued = Long.parseLong(element.getAttribute(ISSUED));
            final long until = EXPIRES.equals(element.getName())
                    ? Long.parseLong(element.getAttribute(UNTIL)) : 0;
            return new Command(element.getName(), issued, element.getAttribute(MODE), until);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** Only admin@ of the account's own server may wipe. */
    public static boolean isFromAdmin(final Account account, final Jid from) {
        return isFromAdmin(account.getJid(), from);
    }

    static boolean isFromAdmin(final Jid account, final Jid from) {
        if (account == null || from == null) {
            return false;
        }
        final Jid bare = from.asBareJid();
        return ADMIN.equals(bare.getLocal())
                && bare.getDomain().equals(account.getDomain());
    }

    /**
     * Whether a command issued at {@code issued} is meant for this phone: the account was here
     * already. A phone set up later (a new phone after a lost one) never wipes itself.
     */
    public static boolean issuedForThisPhone(final Context context, final Account account, final long issued) {
        final long added = VeloDeviceAlert.addedAt(context, account);
        // W1 (sotto-punto): se non sappiamo quando l'account fu aggiunto (added==0, prima che Velo
        // registrasse il tempo) NON onoriamo un wipe: un "issued" qualsiasi non deve valere. Solo
        // gli account con un tempo d'aggiunta noto possono essere cancellati a distanza.
        return added != 0 && issued >= added;
    }

    /**
     * Velo 0.49.1 (W1): le impronte IBRIDE PQ OMEMO2 del dispositivo admin inchiodate nell'app
     * (velo.admin.omemo in velo-local.properties, separate da virgola) — quelle mostrate come
     * "Impronta PQ OMEMO2" nei dettagli del contatto. Come la chiave TLS del server, la chiave
     * admin e' nell'app, non appresa dal server: un server ostile che pubblica una chiave "admin"
     * falsa (e il telefono la fida alla cieca) non puo' cancellare nulla. 0.49.2: il confronto usa
     * l'impronta ibrida del mittente (vedi AxolotlService.hybridFingerprintForDevice), non quella
     * classica grezza. Nessuna impronta inchiodata -> il wipe a distanza e' disattivato.
     */
    public static Set<String> adminKeys() {
        return parseAdminKeys(eu.siacs.conversations.Config.VELO_ADMIN_OMEMO);
    }

    /** Parses and normalizes a comma-separated list of admin fingerprints (testable, no Config). */
    static Set<String> parseAdminKeys(@Nullable final String raw) {
        final Set<String> keys = new HashSet<>();
        if (raw == null) {
            return keys;
        }
        for (final String part : raw.split(",")) {
            final String normalized = normalizeFingerprint(part);
            if (normalized != null) {
                keys.add(normalized);
            }
        }
        return keys;
    }

    /** Whether the device whose OMEMO fingerprint this is is a pinned admin device. */
    public static boolean isFromPinnedAdminKey(@Nullable final String fingerprint) {
        return matchesAdminKey(eu.siacs.conversations.Config.VELO_ADMIN_OMEMO, fingerprint);
    }

    /** The same, with the pinned list passed in (testable, no Config). */
    static boolean matchesAdminKey(@Nullable final String raw, @Nullable final String fingerprint) {
        final Set<String> pinned = parseAdminKeys(raw);
        if (pinned.isEmpty()) {
            return false; // nessuna chiave admin nell'app -> non si cancella mai a distanza
        }
        final String normalized = normalizeFingerprint(fingerprint);
        return normalized != null && pinned.contains(normalized);
    }

    /** Solo le cifre esadecimali, minuscole (via spazi, due punti, ecc.), per confrontare le impronte. */
    @Nullable
    static String normalizeFingerprint(@Nullable final String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(fingerprint.length());
        for (int i = 0; i < fingerprint.length(); i++) {
            final char c = Character.toLowerCase(fingerprint.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Nullable
    private static Element find(final Message message) {
        for (final Element payload : message.getPayloads()) {
            if (is(payload)) {
                return payload;
            }
        }
        return null;
    }

    // ---- admin side: the wipes sent and not answered yet ----

    /** One wipe the admin sent: user, when, what to do on the answer. */
    public static final class Pending {
        public final String admin;
        public final Jid user;
        public final long issued;
        public final String mode;

        Pending(final String admin, final Jid user, final long issued, final String mode) {
            this.admin = admin;
            this.user = user;
            this.issued = issued;
            this.mode = mode;
        }

        String encode() {
            return admin + "|" + user.asBareJid() + "|" + issued + "|" + mode;
        }

        @Nullable
        static Pending decode(final String value) {
            final String[] parts = value.split("\\|");
            if (parts.length != 4) {
                return null;
            }
            try {
                return new Pending(parts[0], Jid.of(parts[1]), Long.parseLong(parts[2]), parts[3]);
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
    }

    public static void addPending(
            final Context context, final Account admin, final Jid user, final long issued, final String mode) {
        final Set<String> all = new HashSet<>();
        for (final Pending pending : pending(context)) {
            if (!pending.user.equals(user.asBareJid()) || !pending.admin.equals(admin.getUuid())) {
                all.add(pending.encode());
            }
        }
        all.add(new Pending(admin.getUuid(), user.asBareJid(), issued, mode).encode());
        preferences(context).edit().putStringSet(PENDING, all).commit();
    }

    public static Set<Pending> pending(final Context context) {
        final Set<Pending> result = new HashSet<>();
        for (final String value : preferences(context).getStringSet(PENDING, new HashSet<>())) {
            final Pending pending = Pending.decode(value);
            if (pending != null) {
                result.add(pending);
            }
        }
        return result;
    }

    @Nullable
    public static Pending findPending(final Context context, final Account admin, final Jid user) {
        for (final Pending pending : pending(context)) {
            if (pending.admin.equals(admin.getUuid()) && pending.user.equals(user.asBareJid())) {
                return pending;
            }
        }
        return null;
    }

    public static void removePending(final Context context, final Account admin, final Jid user) {
        final Set<String> all = new HashSet<>();
        for (final Pending pending : pending(context)) {
            if (!pending.admin.equals(admin.getUuid()) || !pending.user.equals(user.asBareJid())) {
                all.add(pending.encode());
            }
        }
        preferences(context).edit().putStringSet(PENDING, all).commit();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
