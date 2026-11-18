package eu.siacs.conversations.velo;

import eu.siacs.conversations.xml.Namespace;

/** Velo: the pubsub nodes of upstream's Blog and Stories, which are published outside OMEMO. */
public final class VeloFeeds {

    private VeloFeeds() {
    }

    public static boolean isPublicFeed(final String node) {
        return node != null
                && (node.startsWith(Namespace.MICROBLOG)
                || node.startsWith(Namespace.PUBSUB_SOCIAL_FEED)
                || node.startsWith(Namespace.PUBSUB_STORIES));
    }
}
