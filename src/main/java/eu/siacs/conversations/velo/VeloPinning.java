package eu.siacs.conversations.velo;

import android.util.Base64;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.util.Locale;

import okhttp3.Interceptor;

/**
 * Velo: the server's key is built into the app. A connection to a server is accepted only if its
 * certificate carries one of that server's pinned keys (SHA-256 of the public key, base64): a
 * forged certificate, even one a real authority signed, is refused.
 *
 * <p>Velo 0.39: pins are per server (see {@link VeloServers}). The built-in server's pins come from
 * {@code velo-local.properties}; an invited server's come from its invite. When the built-in server
 * is pinned, a server Velo has no pins for is refused. A build from the published source has no
 * built-in pins and then accepts any server, except that an invited server's pins still apply.
 */
public final class VeloPinning {

    private VeloPinning() {}

    /** Whether the built-in server is pinned (a release build). */
    public static boolean isEnabled() {
        return VeloServers.pinningEnabled();
    }

    /** Whether a connection to this server must show a pinned key. */
    public static boolean required(final String server) {
        return required(VeloServers.get(server));
    }

    /** Velo 0.39.1: the same, with the account's own server record (see VeloServers.forAccount). */
    public static boolean required(final eu.siacs.conversations.entities.Account account) {
        return required(VeloServers.forAccount(account));
    }

    private static boolean required(@androidx.annotation.Nullable final VeloServers.Server known) {
        return (known != null && !known.pins.isEmpty()) || isEnabled();
    }

    /** Whether the server certificate (first of the chain) carries one of the server's pinned keys. */
    public static boolean matches(final String server, final Certificate[] chain) {
        return matches(VeloServers.get(server), chain);
    }

    /** Velo 0.39.1: the same, with the account's own server record. */
    public static boolean matches(final eu.siacs.conversations.entities.Account account, final Certificate[] chain) {
        return matches(VeloServers.forAccount(account), chain);
    }

    private static boolean matches(@androidx.annotation.Nullable final VeloServers.Server known, final Certificate[] chain) {
        if (!required(known)) {
            return true;
        }
        if (known == null || known.pins.isEmpty()) {
            return false;
        }
        if (chain == null || chain.length == 0) {
            return false;
        }
        final MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            return false;
        }
        final byte[] key = chain[0].getPublicKey().getEncoded();
        return known.pins.contains(Base64.encodeToString(sha256.digest(key), Base64.NO_WRAP));
    }

    /** The account's server or one of its services (upload., conference., ...). */
    public static boolean isOwnHost(final String host, final String server) {
        final String h = host.toLowerCase(Locale.ROOT);
        final String s = server.toLowerCase(Locale.ROOT);
        return h.equals(s) || h.endsWith("." + s);
    }

    /** For HTTP uploads and downloads: refuses the account's own server if its key isn't pinned. */
    public static Interceptor interceptor(final String server) {
        return interceptor(server, () -> VeloServers.get(server));
    }

    /** Velo 0.39.1: the same, with the account's own server record. */
    public static Interceptor interceptor(final eu.siacs.conversations.entities.Account account) {
        return interceptor(account.getServer(), () -> VeloServers.forAccount(account));
    }

    private static Interceptor interceptor(final String server,
            final java.util.function.Supplier<VeloServers.Server> record) {
        return chain -> {
            final var connection = chain.connection();
            final VeloServers.Server known = record.get();
            if (connection != null && isOwnHost(chain.request().url().host(), server) && required(known)) {
                // No TLS at all counts as a wrong key
                final var handshake = connection.handshake();
                if (handshake == null
                        || !matches(known, handshake.peerCertificates().toArray(new Certificate[0]))) {
                    throw new IOException("server key does not match the one built into Velo");
                }
            }
            return chain.proceed(chain.request());
        };
    }
}
