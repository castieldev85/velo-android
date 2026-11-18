package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

import eu.siacs.conversations.xmpp.Jid;

public class VeloContactsTest {

    private static final Jid ACCOUNT = Jid.of("admin@example.org/velo");

    @Test
    public void bareNameIsAUserOfTheOwnServer() {
        Assert.assertEquals(Jid.of("mario@example.org"), VeloContacts.typedJid(ACCOUNT, "mario"));
        Assert.assertEquals(Jid.of("mario@example.org"), VeloContacts.typedJid(ACCOUNT, "mario@example.org"));
    }

    @Test
    public void ownServerNameIsTheServerItself() {
        final Jid server = VeloContacts.typedJid(ACCOUNT, "example.org");
        Assert.assertEquals(Jid.ofDomain("example.org"), server);
        Assert.assertTrue(VeloContacts.isServerItself(ACCOUNT, server));
        Assert.assertTrue(VeloContacts.isServerItself(ACCOUNT, VeloContacts.typedJid(ACCOUNT, "Example.ORG")));
    }

    @Test
    public void otherDomainsAreNotTheServerItself() {
        Assert.assertFalse(VeloContacts.isServerItself(ACCOUNT, Jid.ofDomain("conference.example.org")));
        Assert.assertFalse(VeloContacts.isServerItself(ACCOUNT, Jid.ofDomain("other.net")));
        Assert.assertFalse(VeloContacts.isServerItself(ACCOUNT, Jid.of("mario@example.org")));
        Assert.assertFalse(VeloContacts.isServerItself(null, Jid.ofDomain("example.org")));
        Assert.assertEquals(Jid.of("other.net@example.org"), VeloContacts.typedJid(ACCOUNT, "other.net"));
    }
}
