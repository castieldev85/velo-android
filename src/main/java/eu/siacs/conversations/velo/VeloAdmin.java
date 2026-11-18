package eu.siacs.conversations.velo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
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
 * Velo 0.32: user management for the server's administrator, through the commands ejabberd already
 * offers to the "admin" acl (XEP-0133, mod_configure). The server decides who may run them; here
 * there is only the protocol. Lists answer in one step, the other commands in two: execute, then
 * complete with the session id.
 */
public final class VeloAdmin {

    public static final String PREFIX = "http://jabber.org/protocol/admin#";
    public static final String LIST_USERS = "get-registered-users-list";
    public static final String LIST_ONLINE = "get-online-users-list";
    public static final String LIST_DISABLED = "get-disabled-users-list";
    public static final String ADD = "add-user";
    public static final String PASSWORD = "change-user-password";
    public static final String DISABLE = "disable-user";
    public static final String ENABLE = "reenable-user";
    public static final String KICK = "end-user-session";
    public static final String DELETE = "delete-user";

    private static final String FORM_TYPE = "http://jabber.org/protocol/admin";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private VeloAdmin() {}

    /** A new user's name: lower case letters, digits and . _ - (also what Velo shows as contact). */
    public static String cleanName(final String typed) {
        final String name = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        return NAME.matcher(name).matches() ? name : null;
    }

    /** Whether the server's command list (disco#items of the commands node) has the admin ones. */
    public static boolean offersAdmin(final Iq items) {
        if (items == null || items.getType() != Iq.Type.RESULT) {
            return false;
        }
        final Element query = items.findChild("query", Namespace.DISCO_ITEMS);
        if (query == null) {
            return false;
        }
        for (final Element item : query.getChildren()) {
            if ((PREFIX + ADD).equals(item.getAttribute("node"))) {
                return true;
            }
        }
        return false;
    }

    public static Iq execute(final Jid server, final String command) {
        final Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(server);
        final Element element = iq.addChild("command", Namespace.COMMANDS);
        element.setAttribute("node", PREFIX + command);
        element.setAttribute("action", "execute");
        return iq;
    }

    public static Iq complete(
            final Jid server,
            final String command,
            final String sessionId,
            final Map<String, List<String>> fields) {
        final Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(server);
        final Element element = iq.addChild("command", Namespace.COMMANDS);
        element.setAttribute("node", PREFIX + command);
        element.setAttribute("action", "complete");
        if (sessionId != null) {
            element.setAttribute("sessionid", sessionId);
        }
        final Data data = new Data();
        data.setFormType(FORM_TYPE);
        for (final Map.Entry<String, List<String>> field : fields.entrySet()) {
            data.put(field.getKey(), field.getValue());
        }
        data.submit();
        element.addChild(data);
        return iq;
    }

    /** The fields a command needs for one user (the jid-single or jid-multi field it asks for). */
    public static Map<String, List<String>> forUser(
            final String command, final Jid user, final String password) {
        final Map<String, List<String>> fields = new LinkedHashMap<>();
        final List<String> jid = Collections.singletonList(user.asBareJid().toString());
        switch (command) {
            case ADD -> {
                fields.put("accountjid", jid);
                fields.put("password", Collections.singletonList(password));
                fields.put("password-verify", Collections.singletonList(password));
            }
            case PASSWORD -> {
                fields.put("accountjid", jid);
                fields.put("password", Collections.singletonList(password));
            }
            case KICK -> fields.put("accountjid", jid);
            case DISABLE, ENABLE, DELETE -> fields.put("accountjids", jid);
            default -> throw new IllegalArgumentException(command);
        }
        return fields;
    }

    public static String sessionId(final Iq response) {
        final Element command = response.findChild("command", Namespace.COMMANDS);
        return command == null ? null : command.getAttribute("sessionid");
    }

    public static boolean completed(final Iq response) {
        if (response == null || response.getType() != Iq.Type.RESULT) {
            return false;
        }
        final Element command = response.findChild("command", Namespace.COMMANDS);
        return command != null && "completed".equals(command.getAttribute("status"));
    }

    /** The jids in a list command's answer, sorted, without the resource. */
    public static List<Jid> jids(final Iq response, final String var) {
        final List<Jid> jids = new ArrayList<>();
        if (response == null || response.getType() != Iq.Type.RESULT) {
            return jids;
        }
        final Element command = response.findChild("command", Namespace.COMMANDS);
        final Element x = command == null ? null : command.findChild("x", Namespace.DATA);
        final Field field = x == null ? null : Data.parse(x).getFieldByName(var);
        if (field == null) {
            return jids;
        }
        for (final String value : field.getValues()) {
            try {
                jids.add(Jid.of(value).asBareJid());
            } catch (final IllegalArgumentException e) {
                // not a jid, skipped
            }
        }
        // by name, so "prova" comes before "prova2" ('@' sorts after the digits)
        jids.sort((a, b) -> String.valueOf(a.getLocal()).compareTo(String.valueOf(b.getLocal())));
        return jids;
    }

    /** Asks the server's command list; answers on a background thread. */
    public static void checkAdmin(
            final XmppConnectionService service, final Account account, final Consumer<Boolean> done) {
        if (!account.isOnlineAndConnected()) {
            done.accept(false);
            return;
        }
        service.fetchCommands(account, account.getDomain(), iq -> done.accept(offersAdmin(iq)));
    }

    /** A list command: the answer arrives at the first step. */
    public static void list(
            final XmppConnectionService service,
            final Account account,
            final String command,
            final String var,
            final Consumer<List<Jid>> done) {
        service.sendIqPacket(
                account,
                execute(account.getDomain(), command),
                iq -> done.accept(completed(iq) ? jids(iq, var) : null));
    }

    /** A command on one user: execute, then complete with the fields. Answers true if done. */
    public static void run(
            final XmppConnectionService service,
            final Account account,
            final String command,
            final Map<String, List<String>> fields,
            final Consumer<Boolean> done) {
        final Jid server = account.getDomain();
        service.sendIqPacket(
                account,
                execute(server, command),
                first -> {
                    if (first == null || first.getType() != Iq.Type.RESULT) {
                        done.accept(false);
                        return;
                    }
                    service.sendIqPacket(
                            account,
                            complete(server, command, sessionId(first), fields),
                            second -> done.accept(completed(second)));
                });
    }
}
