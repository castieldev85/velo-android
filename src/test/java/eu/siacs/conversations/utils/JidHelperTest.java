package eu.siacs.conversations.utils;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

public class JidHelperTest {

    @Test
    public void domainOnlyJidDoesNotCrash() {
        // a MUC may report an occupant's real JID as a bare domain. Velo: never a piece of the
        // server's domain, the generic name instead (VeloNames)
        Assert.assertEquals("Server", JidHelper.localPartOrFallback(Jid.ofDomain("example.com")));
    }

    @Test
    public void velo_blacklistedLocalPartIsKept() {
        // Velo: a user really called "xmpp" is shown as such, not as a piece of the domain
        Assert.assertEquals("xmpp", JidHelper.localPartOrFallback(Jid.of("xmpp@example.com")));
    }

    @Test
    public void localPartIsUsed() {
        Assert.assertEquals("alice", JidHelper.localPartOrFallback(Jid.of("alice@example.com")));
    }
}
