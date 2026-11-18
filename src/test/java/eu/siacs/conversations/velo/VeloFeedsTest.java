package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

public class VeloFeedsTest {

    @Test
    public void blogAndStoriesAreBlocked() {
        Assert.assertTrue(VeloFeeds.isPublicFeed("urn:xmpp:microblog:0"));
        Assert.assertTrue(VeloFeeds.isPublicFeed("urn:xmpp:microblog:0:comments/abc"));
        Assert.assertTrue(VeloFeeds.isPublicFeed("urn:xmpp:pubsub-social-feed:1"));
        Assert.assertTrue(VeloFeeds.isPublicFeed("urn:xmpp:pubsub-social-feed:stories:0"));
    }

    @Test
    public void everythingElseStillPasses() {
        Assert.assertFalse(VeloFeeds.isPublicFeed(null));
        Assert.assertFalse(VeloFeeds.isPublicFeed("urn:xmpp:avatar:metadata"));
        Assert.assertFalse(VeloFeeds.isPublicFeed("http://jabber.org/protocol/nick"));
        Assert.assertFalse(VeloFeeds.isPublicFeed("urn:xmpp:omemo:2:devices"));
        Assert.assertFalse(VeloFeeds.isPublicFeed("eu.siacs.conversations.axolotl.devicelist"));
        Assert.assertFalse(VeloFeeds.isPublicFeed("urn:xmpp:bookmarks:1"));
    }
}
