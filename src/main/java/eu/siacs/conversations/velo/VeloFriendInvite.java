package eu.siacs.conversations.velo;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.forms.Field;
import im.conversations.android.xmpp.model.stanza.Iq;

/**
 * Velo 0.42: "Invite a friend". Anyone on the public server asks it for a one-time invite to
 * create an account (XEP-0401, ejabberd mod_invites: at most 5 per person, valid 7 days) and sends
 * a link to the site. The site page offers the download of Velo and opens the invite in Velo; the
 * code travels after the '#', which the browser never sends to any server.
 *
 * <p>The invite is the usual {@code velo:invite?} text (server, onion, keys, name) with two more
 * parameters: {@code t}, the registration token (XEP-0445 pre-authenticated registration), and
 * {@code i}, the user name of who invites, added as a contact after the first login. Velo before
 * 0.42 ignores both and only offers the login.
 */
public final class VeloFriendInvite {

    public static final String NODE = "urn:xmpp:invite#create-account";
    /** The page that offers the download and opens the invite (Velo's public site). */
    public static final String PAGE = "https://velochat.eu/invito.html#";
    public static final String EXTRA_TOKEN = "velo_invite_token";
    public static final String EXTRA_INVITER = "velo_invite_inviter";
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9._-]{1,64}$");
    private static final Pattern PREAUTH = Pattern.compile("[?;&]preauth=([^;&#]+)");
    private static final Pattern LIMIT = Pattern.compile("(?i)maximum|limit|too many|exceed");
    private static final String PREFERENCES = "velo_friend_invite";
    private static final String INVITER = "inviter_";

    private VeloFriendInvite() {}

    /** What the server answered: a token, or why there is none. */
    public static final class Result {
        @Nullable public final String token;
        /** Null when it worked; otherwise the XMPP error condition, or "timeout". */
        @Nullable public final String error;

        Result(@Nullable final String token, @Nullable final String error) {
            this.token = token;
            this.error = error;
        }

        /** The server refused because this person has used up their invites. */
        public boolean limitReached() {
            return "resource-constraint".equals(error) || "policy-violation".equals(error);
        }

        /** The server does not let ordinary users invite (a private server). */
        public boolean notAllowed() {
            return "forbidden".equals(error) || "not-allowed".equals(error)
                    || "item-not-found".equals(error) || "feature-not-implemented".equals(error);
        }
    }

    /** Asks the account's server for a new invite. Answers on a background thread. */
    public static void create(
            final XmppConnectionService service, final Account account, final Consumer<Result> done) {
        if (!account.isOnlineAndConnected()) {
            done.accept(new Result(null, "offline"));
            return;
        }
        final Jid server = account.getDomain();
        service.sendIqPacket(account, command(server, "execute", null, null), first -> {
            final Result direct = read(first);
            if (direct.token != null || direct.error != null) {
                done.accept(direct);
                return;
            }
            // The server asks for its form first (XEP-0401 lets it): send it back as it is
            final Element cmd = first.findChild("command", Namespace.COMMANDS);
            final Element x = cmd == null ? null : cmd.findChild("x", Namespace.DATA);
            final Data form = new Data();
            if (x != null) {
                final Data asked = Data.parse(x);
                if (asked.getFormType() != null) {
                    form.setFormType(asked.getFormType());
                }
            }
            form.submit();
            service.sendIqPacket(
                    account,
                    command(server, "complete", cmd == null ? null : cmd.getAttribute("sessionid"), form),
                    second -> {
                        final Result result = read(second);
                        done.accept(result.token != null || result.error != null
                                ? result
                                : new Result(null, "no-token"));
                    });
        });
    }

    private static Iq command(
            final Jid server, final String action, @Nullable final String session, @Nullable final Data form) {
        final Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(server);
        final Element element = iq.addChild("command", Namespace.COMMANDS);
        element.setAttribute("node", NODE);
        element.setAttribute("action", action);
        if (session != null) {
            element.setAttribute("sessionid", session);
        }
        if (form != null) {
            element.addChild(form);
        }
        return iq;
    }

    /** The token in an answer; neither token nor error when the command wants its form back. */
    private static Result read(@Nullable final Iq response) {
        if (response == null || response.getType() == Iq.Type.TIMEOUT) {
            return new Result(null, "timeout");
        }
        if (response.getType() != Iq.Type.RESULT) {
            final String condition = response.getErrorCondition();
            final var error = response.getError();
            final String text = error == null ? null : error.getTextAsString();
            Log.d("velo", "friend invite refused: " + condition + " (" + text + ")");
            // ejabberd 26 answers bad-request with a text when the user has no invites left
            if ("bad-request".equals(condition) && text != null && LIMIT.matcher(text).find()) {
                return new Result(null, "resource-constraint");
            }
            return new Result(null, condition == null ? "error" : condition);
        }
        final Element command = response.findChild("command", Namespace.COMMANDS);
        final Element x = command == null ? null : command.findChild("x", Namespace.DATA);
        if (x != null) {
            final Field uri = Data.parse(x).getFieldByName("uri");
            final String token = uri == null ? null : tokenFromUri(uri.getValue());
            if (token != null) {
                return new Result(token, null);
            }
        }
        if (command != null && "completed".equals(command.getAttribute("status"))) {
            return new Result(null, "no-token");
        }
        return new Result(null, null);
    }

    /** The token in {@code xmpp:server?register;preauth=TOKEN} (or in a roster invite). */
    @Nullable
    static String tokenFromUri(@Nullable final String uri) {
        if (uri == null) {
            return null;
        }
        final Matcher m = PREAUTH.matcher(uri);
        if (!m.find()) {
            return null;
        }
        final String token = decode(m.group(1));
        return TOKEN.matcher(token).matches() ? token : null;
    }

    /** The invite text: the server's invite with the token and who invites. */
    public static String inviteText(final VeloServers.Server server, final String token, final String inviter) {
        return server.toInvite() + "&t=" + encode(token) + "&i=" + encode(inviter);
    }

    /** The link to send: the site page, with the whole invite after the '#'. */
    public static String link(final VeloServers.Server server, final String token, final String inviter) {
        return PAGE + inviteText(server, token, inviter).substring(VeloServers.PREFIX.length());
    }

    /** The registration token in an invite text, or null. */
    @Nullable
    public static String token(@Nullable final String invite) {
        final String value = param(invite, "t");
        return value != null && TOKEN.matcher(value).matches() ? value : null;
    }

    /** Velo 0.43: the extra holding the user name an invite suggests (never a password). */
    public static final String EXTRA_USER = "velo_invite_user";

    /**
     * Velo 0.43: the user name a trial group page puts in the invite ({@code &u=}), so the login
     * page opens already filled in. Only the name: the password is never in an invite.
     */
    @Nullable
    public static String user(@Nullable final String invite) {
        final String value = param(invite, "u");
        return value != null && NAME.matcher(value).matches() ? value : null;
    }

    /** The user name of who invites, or null. */
    @Nullable
    public static String inviter(@Nullable final String invite) {
        final String value = param(invite, "i");
        return value != null && NAME.matcher(value).matches() ? value : null;
    }

    @Nullable
    private static String param(@Nullable final String invite, final String key) {
        if (invite == null) {
            return null;
        }
        final int start = invite.indexOf('?');
        final String query = start < 0 ? invite : invite.substring(start + 1);
        for (final String part : query.split("&")) {
            final int eq = part.indexOf('=');
            if (eq > 0 && key.equals(part.substring(0, eq))) {
                return decode(part.substring(eq + 1)).trim();
            }
        }
        return null;
    }

    /** After the account is created: who invited, to add as a contact at the first login. */
    public static void rememberInviter(final Context context, final Account account, @Nullable final String inviter) {
        if (inviter == null) {
            return;
        }
        prefs(context).edit().putString(INVITER + account.getJid().asBareJid(), inviter).apply();
    }

    /** Who invited this account, once: the contact is added the first time it is asked. */
    @Nullable
    public static String takeInviter(final Context context, final Account account) {
        final String key = INVITER + account.getJid().asBareJid();
        final String inviter = prefs(context).getString(key, null);
        if (inviter != null) {
            prefs(context).edit().remove(key).apply();
        }
        return inviter;
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String encode(final String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static String decode(final String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
            return value;
        }
    }
}
