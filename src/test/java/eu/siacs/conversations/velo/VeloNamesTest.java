package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

import eu.siacs.conversations.utils.JidHelper;
import eu.siacs.conversations.xmpp.Jid;

public class VeloNamesTest {

    @Test
    public void onlyTheUserNameIsShown() {
        Assert.assertEquals("velo", VeloNames.of(Jid.of("velo@example.org")));
        Assert.assertEquals("velo", VeloNames.of(Jid.of("velo@example.org/phone")));
        Assert.assertEquals("stanza", VeloNames.of(Jid.of("stanza@conference.example.org")));
        Assert.assertEquals("velo", VeloNames.of("velo@example.org"));
        Assert.assertEquals("", VeloNames.of((Jid) null));
    }

    @Test
    public void theServerIsNeverItsDomain() {
        Assert.assertEquals(VeloNames.SERVER_FALLBACK, VeloNames.of(Jid.ofDomain("example.org")));
        Assert.assertEquals(VeloNames.SERVER_FALLBACK, VeloNames.of("example.org"));
    }

    @Test
    public void savedNamesThatAreAddressesAreCleaned() {
        final Jid server = Jid.ofDomain("example.org");
        final Jid velo = Jid.of("velo@example.org");
        Assert.assertEquals(VeloNames.SERVER_FALLBACK, VeloNames.clean("example.org", server));
        Assert.assertEquals("velo", VeloNames.clean("velo@example.org", velo));
        Assert.assertEquals("velo", VeloNames.clean("Velo@Example.org", velo));
        Assert.assertEquals("Mario (velo)", VeloNames.clean("Mario (velo@example.org)", velo));
        Assert.assertEquals("Mario", VeloNames.clean("Mario", velo));
        Assert.assertEquals("velo", VeloNames.clean(null, velo));
        Assert.assertEquals("velo", VeloNames.clean("  ", velo));
    }

    @Test
    public void usersCalledLikeTheProtocolKeepTheirName() {
        // upstream showed the first piece of the domain for these
        Assert.assertEquals("xmpp", JidHelper.localPartOrFallback(Jid.of("xmpp@example.org")));
        Assert.assertEquals("me", JidHelper.localPartOrFallback(Jid.of("me@example.org")));
        Assert.assertEquals("admin", JidHelper.localPartOrFallback(Jid.of("admin@example.org")));
    }
}
