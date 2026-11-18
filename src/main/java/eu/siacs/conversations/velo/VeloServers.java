package eu.siacs.conversations.velo;

import androidx.annotation.Nullable;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Velo 0.39: the servers this app may connect to. One is built in (Config + the pins of
 * velo-local.properties); the others come from an invite QR code made on that server. Each one
 * carries its domain, its onion, its key pins and the group's name. An invited server is kept in
 * its account's keys, inside the encrypted database, and loaded here when the app starts.
 *
 * <p>Invite: {@code velo:invite?v=1&d=<domain>&o=<onion>&p=<pin>,<pin>&n=<name>}. Never a password.
 */
public final class VeloServers {

    public static final String ACCOUNT_KEY = "velo_server";
    public static final String EXTRA_INVITE = "velo_invite";
    static final String PREFIX = "velo:invite?";
    static final String DEFAULT_NAME = "Velo";
    static final int MAX_NAME = 40;
    static final int MAX_PINS = 4;

    private static final Pattern DOMAIN =
            Pattern.compile("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$");
    private static final Pattern ONION = Pattern.compile("^[a-z2-7]{56}\\.onion$");
    private static final Pattern PIN = Pattern.compile("^[A-Za-z0-9+/]{43}=$");

    private static final Map<String, Server> INVITED = new ConcurrentHashMap<>();

    private VeloServers() {}

    public static final class Server {
        public final String domain;
        @Nullable public final String onion;
        public final int port;
        public final Set<String> pins;
        public final String name;
        public final boolean builtIn;

        Server(
                final String domain,
                @Nullable final String onion,
                final int port,
                final Set<String> pins,
                final String name,
                final boolean builtIn) {
            this.domain = domain;
            this.onion = onion;
            this.port = port;
            this.pins = Collections.unmodifiableSet(new LinkedHashSet<>(pins));
            this.name = name;
            this.builtIn = builtIn;
        }

        /** The text of the invite QR code for this server. */
        public String toInvite() {
            final StringBuilder sb = new StringBuilder(PREFIX);
            sb.append("v=1&d=").append(encode(domain));
            if (onion != null) {
                sb.append("&o=").append(encode(onion));
            }
            sb.append("&p=").append(encode(String.join(",", pins)));
            sb.append("&n=").append(encode(name));
            return sb.toString();
        }

        /** Eight characters both phones show, to check the code was not swapped on the way. */
        public String shortCode() {
            final List<String> sorted = new ArrayList<>(pins);
            Collections.sort(sorted);
            final MessageDigest sha256;
            try {
                sha256 = MessageDigest.getInstance("SHA-256");
            } catch (final NoSuchAlgorithmException e) {
                return "";
            }
            final byte[] digest =
                    sha256.digest((domain + "|" + String.join(",", sorted)).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format(Locale.ROOT, "%02X", digest[i]));
            }
            return hex.substring(0, 4) + "-" + hex.substring(4, 8);
        }

        String toJson() {
            try {
                final JSONObject json = new JSONObject();
                json.put("d", domain);
                json.put("o", onion == null ? "" : onion);
                json.put("port", port);
                json.put("p", new JSONArray(pins));
                json.put("n", name);
                return json.toString();
            } catch (final JSONException e) {
                return null;
            }
        }
    }

    /** The server built into the app, or null in a build from the published source. */
    @Nullable
    public static Server builtIn() {
        if (Config.VELO_LOGIN_DOMAIN == null) {
            return null;
        }
        return new Server(
                Config.VELO_LOGIN_DOMAIN.toLowerCase(Locale.ROOT),
                Config.VELO_ONION_HOST,
                Config.VELO_ONION_PORT,
                parsePins(BuildConfig.VELO_SERVER_PINS),
                DEFAULT_NAME,
                true);
    }

    /** Whether the built-in server has pins: then no server without pins is accepted. */
    public static boolean pinningEnabled() {
        final Server builtIn = builtIn();
        return builtIn != null && !builtIn.pins.isEmpty();
    }

    @Nullable
    public static Server get(@Nullable final String domain) {
        if (domain == null) {
            return null;
        }
        final String d = domain.toLowerCase(Locale.ROOT);
        final Server builtIn = builtIn();
        if (builtIn != null && builtIn.domain.equals(d)) {
            return builtIn;
        }
        return INVITED.get(d);
    }

    /**
     * Adds an invited server. A server Velo already knows is never replaced: an invite that names
     * a known domain with another onion or other keys could send an existing account to a forged
     * server. Returns false when the invite conflicts with what Velo knows.
     */
    public static boolean register(@Nullable final Server server) {
        if (server == null) {
            return false;
        }
        final Server known = get(server.domain);
        if (known != null) {
            return sameServer(known, server);
        }
        if (server.builtIn) {
            return false;
        }
        INVITED.putIfAbsent(server.domain, server);
        return sameServer(INVITED.get(server.domain), server);
    }

    /**
     * Velo 0.39.1: forgets the invited servers that no account uses, e.g. after an account was
     * deleted or an invite was not completed. Until then a new invite for that domain is refused.
     */
    public static void forgetUnused(final java.util.Collection<Account> accounts) {
        final Map<String, java.util.List<Server>> used = new java.util.HashMap<>();
        for (final Account account : accounts) {
            final String domain = account.getServer().toLowerCase(Locale.ROOT);
            final java.util.List<Server> records = used.computeIfAbsent(domain, d -> new java.util.ArrayList<>());
            final Server own = fromJson(account.getKey(ACCOUNT_KEY));
            if (own != null && own.domain.equals(domain)) {
                records.add(own);
            }
        }
        retain(used);
    }

    /**
     * Keeps only the domains in use. Velo 0.39.3: a domain still used by another account keeps
     * the onion and keys of an account that is still there, not those of the deleted one (before,
     * the old keys stayed in use until Velo was restarted).
     */
    static void retain(final Map<String, java.util.List<Server>> used) {
        INVITED.keySet().retainAll(used.keySet());
        for (final Map.Entry<String, java.util.List<Server>> entry : used.entrySet()) {
            final Server current = INVITED.get(entry.getKey());
            if (current == null || entry.getValue().isEmpty()) {
                continue;
            }
            boolean stillOwned = false;
            for (final Server record : entry.getValue()) {
                stillOwned |= sameServer(record, current);
            }
            if (!stillOwned) {
                INVITED.put(entry.getKey(), entry.getValue().get(0));
            }
        }
    }

    /** Whether an invite points to the same onion with the same keys as a known server. */
    public static boolean conflicts(final Server invite) {
        final Server known = get(invite.domain);
        return known != null && !sameServer(known, invite);
    }

    static boolean sameServer(@Nullable final Server a, @Nullable final Server b) {
        if (a == null || b == null) {
            return false;
        }
        return a.domain.equals(b.domain)
                && java.util.Objects.equals(a.onion, b.onion)
                && a.port == b.port
                && a.pins.equals(b.pins);
    }

    /**
     * Velo 0.39.1: the server this account was set up for. Its own record comes first: two
     * accounts on the same domain (an old one from before a server was reinstalled and a new one)
     * each keep their own onion and keys, instead of both taking the first one loaded.
     */
    @Nullable
    public static Server forAccount(@Nullable final Account account) {
        if (account == null || account.getServer() == null) {
            return null;
        }
        final String domain = account.getServer().toLowerCase(Locale.ROOT);
        final Server own = fromJson(account.getKey(ACCOUNT_KEY));
        if (own != null && own.domain.equals(domain)) {
            return own;
        }
        return get(domain);
    }

    /** Loads the server an account was invited to, if any. */
    public static void loadFrom(@Nullable final Account account) {
        if (account == null) {
            return;
        }
        final Server server = fromJson(account.getKey(ACCOUNT_KEY));
        if (server != null && server.domain.equals(account.getServer().toLowerCase(Locale.ROOT))) {
            register(server);
        }
    }

    /** Stores the invited server in the account, if the account is on that server. */
    public static void saveTo(@Nullable final Server server, final Account account) {
        if (server == null || server.builtIn) {
            return;
        }
        if (!server.domain.equals(account.getServer().toLowerCase(Locale.ROOT))) {
            return;
        }
        final Server builtIn = builtIn();
        if (builtIn != null && builtIn.domain.equals(server.domain)) {
            return; // the built-in server needs nothing stored
        }
        final String json = server.toJson();
        if (json != null) {
            account.setKey(ACCOUNT_KEY, json);
        }
    }

    @Nullable
    static Server fromJson(@Nullable final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            final JSONObject json = new JSONObject(text);
            final JSONArray array = json.getJSONArray("p");
            final List<String> pins = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                pins.add(array.getString(i));
            }
            final String onion = json.optString("o", "");
            return build(
                    json.getString("d"),
                    onion.isEmpty() ? null : onion,
                    json.optInt("port", 5222),
                    String.join(",", pins),
                    json.optString("n", DEFAULT_NAME));
        } catch (final JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Velo 0.39.3: reads a QR code from the pixels of a picture (ARGB, as Bitmap.getPixels gives
     * them). Plain Java, so it is tested on the JVM. Returns null when there is no QR code.
     */
    @Nullable
    public static String decodeQr(final int width, final int height, final int[] pixels) {
        final com.google.zxing.RGBLuminanceSource source =
                new com.google.zxing.RGBLuminanceSource(width, height, pixels);
        final Map<com.google.zxing.DecodeHintType, Object> hints =
                new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
        hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
        final com.google.zxing.qrcode.QRCodeReader reader = new com.google.zxing.qrcode.QRCodeReader();
        try {
            return reader.decode(new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(source)), hints).getText();
        } catch (final com.google.zxing.ReaderException first) {
            reader.reset();
            try {
                return reader.decode(new com.google.zxing.BinaryBitmap(
                        new com.google.zxing.common.GlobalHistogramBinarizer(source)), hints).getText();
            } catch (final com.google.zxing.ReaderException second) {
                return null;
            }
        }
    }

    /** Velo 0.39.3: the invite may be pasted together with other text (for example a whole message). */
    @Nullable
    public static String findInvite(@Nullable final String text) {
        if (text == null) {
            return null;
        }
        final int start = text.indexOf("velo:invite?");
        if (start < 0) {
            // Velo 0.42: the link of "Invite a friend" carries the invite after the '#'
            final int page = text.indexOf("/invito.html#");
            if (page >= 0) {
                int end = page;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                return PREFIX + text.substring(page + "/invito.html#".length(), end);
            }
            return text.trim();
        }
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    /**
     * Reads an invite. Throws IllegalArgumentException with a short reason when it is not a valid
     * Velo invite: wrong prefix, bad domain, bad onion, no or bad pins.
     */
    public static Server parseInvite(@Nullable final String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty");
        }
        final String trimmed = text.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw new IllegalArgumentException("not a Velo invite");
        }
        final Map<String, String> params = new LinkedHashMap<>();
        for (final String part : trimmed.substring(PREFIX.length()).split("&")) {
            final int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(part.substring(0, eq), decode(part.substring(eq + 1)));
        }
        if (!"1".equals(params.get("v"))) {
            throw new IllegalArgumentException("unknown version");
        }
        final String pins = params.get("p");
        // A '+' left unencoded in a pin becomes a space when decoded
        return build(
                params.get("d"),
                params.get("o"),
                5222,
                pins == null ? null : pins.replace(' ', '+'),
                params.get("n"));
    }

    private static Server build(
            @Nullable final String domain,
            @Nullable final String onion,
            final int port,
            @Nullable final String pins,
            @Nullable final String name) {
        final String d = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (!DOMAIN.matcher(d).matches()) {
            throw new IllegalArgumentException("bad domain");
        }
        final String o = onion == null ? null : onion.trim().toLowerCase(Locale.ROOT);
        if (o == null || !ONION.matcher(o).matches()) {
            throw new IllegalArgumentException("bad onion");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("bad port");
        }
        final Set<String> pinSet = parsePins(pins == null ? "" : pins);
        if (pinSet.isEmpty() || pinSet.size() > MAX_PINS) {
            throw new IllegalArgumentException("bad pins");
        }
        for (final String pin : pinSet) {
            if (!PIN.matcher(pin).matches()) {
                throw new IllegalArgumentException("bad pins");
            }
        }
        return new Server(d, o, port, pinSet, cleanName(name), false);
    }

    /**
     * The account a selector shows only by its user name: the one with that name, on the built-in
     * server first, then on an invited one. Null when none matches.
     */
    @Nullable
    public static eu.siacs.conversations.xmpp.Jid accountJid(
            @Nullable final String typed, @Nullable final Iterable<Account> accounts) {
        if (typed == null || typed.isEmpty()) {
            return null;
        }
        if (typed.indexOf('@') >= 0) {
            return eu.siacs.conversations.xmpp.Jid.of(typed);
        }
        final Server builtIn = builtIn();
        Account match = null;
        if (accounts != null) {
            for (final Account account : accounts) {
                final String local = account.getJid().getLocal();
                if (local == null || !local.equalsIgnoreCase(typed)) {
                    continue;
                }
                if (builtIn != null && builtIn.domain.equalsIgnoreCase(account.getServer())) {
                    return account.getJid().asBareJid();
                }
                if (match == null) {
                    match = account;
                }
            }
        }
        if (match != null) {
            return match.getJid().asBareJid();
        }
        return builtIn == null ? null : eu.siacs.conversations.xmpp.Jid.ofLocalAndDomain(typed, builtIn.domain);
    }

    static String cleanName(@Nullable final String name) {
        if (name == null) {
            return DEFAULT_NAME;
        }
        final StringBuilder sb = new StringBuilder();
        name.codePoints()
                .filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .forEach(sb::appendCodePoint);
        String clean = sb.toString().trim().replaceAll("\\s+", " ");
        if (clean.codePointCount(0, clean.length()) > MAX_NAME) {
            clean = clean.substring(0, clean.offsetByCodePoints(0, MAX_NAME)).trim();
        }
        return clean.isEmpty() ? DEFAULT_NAME : clean;
    }

    static Set<String> parsePins(final String list) {
        final Set<String> pins = new LinkedHashSet<>();
        for (final String pin : list.split(",")) {
            if (!pin.trim().isEmpty()) {
                pins.add(pin.trim());
            }
        }
        return pins;
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
            return "";
        }
    }
}
