package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

public class VeloFriendInviteTest {

    private static final String BASE =
            "velo:invite?v=1&d=example.org&o=abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion"
                    + "&p=AAAA%2BBBB%2FCCCC%3D&n=Velo";

    @Test
    public void tokenFromTheServerUri() {
        Assert.assertEquals("Abc_def-123456", VeloFriendInvite.tokenFromUri("xmpp:example.org?register;preauth=Abc_def-123456"));
        Assert.assertEquals("Abc_def-123456", VeloFriendInvite.tokenFromUri("xmpp:mario@example.org?roster;preauth=Abc_def-123456;ibr=y"));
        Assert.assertNull(VeloFriendInvite.tokenFromUri("xmpp:example.org?register"));
        Assert.assertNull(VeloFriendInvite.tokenFromUri("xmpp:example.org?register;preauth=bad<token>"));
        Assert.assertNull(VeloFriendInvite.tokenFromUri(null));
    }

    @Test
    public void tokenAndInviterFromTheInvite() {
        final String invite = BASE + "&t=Abc_def-123456&i=mario";
        Assert.assertEquals("Abc_def-123456", VeloFriendInvite.token(invite));
        Assert.assertEquals("mario", VeloFriendInvite.inviter(invite));
        Assert.assertNull(VeloFriendInvite.token(BASE));
        Assert.assertNull(VeloFriendInvite.inviter(BASE + "&i=Mario%20Rossi"));
    }

    @Test
    public void siteLinkBecomesTheInvite() {
        final String link = "Ti invito su Velo: https://velochat.eu/invito.html#v=1&d=example.org&t=Abc_def-123456&i=mario ciao";
        Assert.assertEquals("velo:invite?v=1&d=example.org&t=Abc_def-123456&i=mario", VeloServers.findInvite(link));
        Assert.assertEquals(BASE, VeloServers.findInvite("guarda " + BASE + " qui"));
    }
}
