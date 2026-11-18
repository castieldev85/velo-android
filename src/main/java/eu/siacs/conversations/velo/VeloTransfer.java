package eu.siacs.conversations.velo;

import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Velo: the wire of the move to a new phone. The old phone listens on the local network, the QR
 * carries its address and a random key, and everything travels in AES-GCM frames under that key.
 * No server and no Tor: the two phones talk to each other and to nothing else.
 */
public final class VeloTransfer {

    private static final String PREFIX = "velo-transfer:1:";
    private static final Pattern CODE =
            Pattern.compile("^velo-transfer:1:(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5}):([0-9a-f]{64})$");
    static final int MAX_FRAME = 1024 * 1024;
    static final int CHUNK = 256 * 1024;
    private static final byte OLD_PHONE = 1;
    private static final byte NEW_PHONE = 2;

    private VeloTransfer() {}

    /** Where to connect and with which key, as read from the QR. */
    public static final class Code {
        public final String host;
        public final int port;
        public final byte[] key;

        Code(final String host, final int port, final byte[] key) {
            this.host = host;
            this.port = port;
            this.key = key;
        }

        @Override
        public String toString() {
            return PREFIX + host + ":" + port + ":" + hex(key);
        }
    }

    public static Code newCode(final String host, final int port) {
        final byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new Code(host, port, key);
    }

    /** Only a numeric address on the local network: a code can't send Velo to the internet. */
    @Nullable
    public static Code parse(@Nullable final String text) {
        if (text == null) {
            return null;
        }
        final Matcher matcher = CODE.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        final String host = matcher.group(1);
        final int port = Integer.parseInt(matcher.group(2));
        if (port < 1 || port > 65535 || !isLocal(host)) {
            return null;
        }
        return new Code(host, port, unhex(matcher.group(3)));
    }

    static boolean isLocal(final String host) {
        final String[] parts = host.split("\\.");
        final int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = Integer.parseInt(parts[i]);
            if (bytes[i] > 255) {
                return false;
            }
        }
        return bytes[0] == 10
                || bytes[0] == 127
                || (bytes[0] == 192 && bytes[1] == 168)
                || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31);
    }

    /** This phone's address on the Wi-Fi or its hotspot, or null. */
    @Nullable
    public static String localAddress() {
        String found = null;
        try {
            for (final NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (final InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        final String name = network.getName();
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                            return address.getHostAddress();
                        }
                        if (found == null) {
                            found = address.getHostAddress();
                        }
                    }
                }
            }
        } catch (final IOException e) {
            return null;
        }
        return found;
    }

    /** A folder name from the other phone can't lead outside the folder it is meant for. */
    @Nullable
    public static File safeChild(final File root, final String relative) throws IOException {
        if (relative == null
                || relative.isEmpty()
                || relative.startsWith("/")
                || relative.contains("\\")
                || relative.contains("\u0000")) {
            return null;
        }
        for (final String part : relative.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return null;
            }
        }
        final File child = new File(root, relative);
        return child.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator) ? child : null;
    }

    /** Encrypted frames in both directions. Each one is numbered: none can be swapped or dropped. */
    public static final class Channel {
        private final DataInputStream in;
        private final DataOutputStream out;
        private final SecretKeySpec key;
        private final byte sendSide;
        private final byte receiveSide;
        private long sent = 0;
        private long received = 0;

        public Channel(
                final DataInputStream in,
                final DataOutputStream out,
                final byte[] key,
                final boolean oldPhone) {
            this.in = in;
            this.out = out;
            this.key = new SecretKeySpec(key, "AES");
            this.sendSide = oldPhone ? OLD_PHONE : NEW_PHONE;
            this.receiveSide = oldPhone ? NEW_PHONE : OLD_PHONE;
        }

        public void send(final byte[] plain, final int length) throws IOException {
            try {
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, nonce(sendSide, sent++));
                final byte[] sealed = cipher.doFinal(plain, 0, length);
                out.writeInt(sealed.length);
                out.write(sealed);
                out.flush();
            } catch (final GeneralSecurityException e) {
                throw new IOException("frame not sealed", e);
            }
        }

        public void send(final byte[] plain) throws IOException {
            send(plain, plain.length);
        }

        public byte[] receive() throws IOException {
            final int length = in.readInt();
            if (length < 16 || length > MAX_FRAME) {
                throw new IOException("bad frame size");
            }
            final byte[] sealed = new byte[length];
            in.readFully(sealed);
            try {
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, nonce(receiveSide, received++));
                return cipher.doFinal(sealed);
            } catch (final GeneralSecurityException e) {
                throw new IOException("frame not authentic", e);
            }
        }

        private static GCMParameterSpec nonce(final byte side, final long counter) {
            final ByteBuffer nonce = ByteBuffer.allocate(12);
            nonce.put(side).put(new byte[3]).putLong(counter);
            return new GCMParameterSpec(128, nonce.array());
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder text = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", b));
        }
        return text.toString();
    }

    private static byte[] unhex(final String text) {
        final byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
