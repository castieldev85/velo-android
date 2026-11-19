package eu.siacs.conversations.velo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.Assert;
import org.junit.Test;

public class VeloSignupTest {

    @Test
    public void zeroBitsCountsLeadingZeros() {
        Assert.assertEquals(0, VeloSignup.zeroBits(new byte[] {(byte) 0x80, 0}));
        Assert.assertEquals(7, VeloSignup.zeroBits(new byte[] {0x01, 0}));
        Assert.assertEquals(8, VeloSignup.zeroBits(new byte[] {0x00, (byte) 0xff}));
        Assert.assertEquals(12, VeloSignup.zeroBits(new byte[] {0x00, 0x0f}));
        Assert.assertEquals(16, VeloSignup.zeroBits(new byte[] {0x00, 0x00}));
    }

    /** Same puzzle as vetrina.py: SHA-256 of "challenge:n" with the given leading zero bits. */
    @Test
    public void solvesTheSitePuzzle() throws Exception {
        final String challenge = "1763560000.0123456789abcdef0123456789abcdef.ff";
        final long n = VeloSignup.solve(challenge, 12);
        final byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((challenge + ":" + n).getBytes(StandardCharsets.US_ASCII));
        Assert.assertTrue(VeloSignup.zeroBits(digest) >= 12);
        for (long m = 0; m < n; m++) {
            final byte[] before = MessageDigest.getInstance("SHA-256")
                    .digest((challenge + ":" + m).getBytes(StandardCharsets.US_ASCII));
            Assert.assertTrue(VeloSignup.zeroBits(before) < 12);
        }
    }

    /** The invite the site gives: "Velo" invites, so the usual friend-invite flow applies. */
    @Test
    public void siteInviteReadsAsAFriendInvite() {
        final String invite =
                "velo:invite?v=1&d=example.org&o=abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion"
                        + "&p=" + "A".repeat(43) + "%3D&n=Velo&t=Abc_def-123456";
        Assert.assertEquals("Abc_def-123456", VeloFriendInvite.token(invite));
        Assert.assertNull(VeloFriendInvite.inviter(invite));
        Assert.assertNull(VeloFriendInvite.user(invite));
        Assert.assertEquals("Velo", VeloServers.parseInvite(invite).name);
    }
}
