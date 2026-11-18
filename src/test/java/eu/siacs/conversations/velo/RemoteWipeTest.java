package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

public class RemoteWipeTest {

    private static final Jid ACCOUNT = Jid.of("mario@example.org/velo");

    @Test
    public void onlyTheOwnServersAdminMayWipe() {
        Assert.assertTrue(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@example.org")));
        Assert.assertTrue(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@example.org/Velo.x1")));
    }

    @Test
    public void othersMayNotWipe() {
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("luigi@example.org")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@other.net")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@example.org.evil.net")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.ofDomain("example.org")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, null));
    }

    @Test
    public void commandsAreRecognised() {
        final Element wipe = RemoteWipe.createWipe(1234L, RemoteWipe.MODE_DELETE);
        Assert.assertTrue(RemoteWipe.is(wipe));
        Assert.assertEquals("1234", wipe.getAttribute("issued"));
        Assert.assertTrue(RemoteWipe.is(RemoteWipe.createWiped(1234L)));
        Assert.assertFalse(RemoteWipe.is(new Element("wipe", "urn:other")));
        Assert.assertFalse(RemoteWipe.is(HistoryPurge.create(1234L)));
    }
}
