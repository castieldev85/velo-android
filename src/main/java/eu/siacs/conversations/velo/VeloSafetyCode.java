package eu.siacs.conversations.velo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Velo: the code two people read to each other to check that nobody sits between them. It covers
 * both accounts and both keys, hybrid fingerprints included, and comes out the same on both phones.
 */
public final class VeloSafetyCode {

    private static final int GROUPS = 8;
    private static final int GROUP_BYTES = 3;

    private VeloSafetyCode() {}

    public static String compute(
            final String jidOne, final String keyOne, final String jidTwo, final String keyTwo) {
        final String one = jidOne.toLowerCase(Locale.ROOT) + "\n" + keyOne.toLowerCase(Locale.ROOT);
        final String two = jidTwo.toLowerCase(Locale.ROOT) + "\n" + keyTwo.toLowerCase(Locale.ROOT);
        final String joined = one.compareTo(two) <= 0 ? one + "\n" + two : two + "\n" + one;
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final StringBuilder code = new StringBuilder();
        for (int group = 0; group < GROUPS; group++) {
            int value = 0;
            for (int i = 0; i < GROUP_BYTES; i++) {
                value = (value << 8) | (digest[group * GROUP_BYTES + i] & 0xFF);
            }
            if (group > 0) {
                code.append(group == GROUPS / 2 ? '\n' : ' ');
            }
            code.append(String.format(Locale.ROOT, "%05d", value % 100000));
        }
        return code.toString();
    }
}
