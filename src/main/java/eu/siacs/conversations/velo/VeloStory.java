package eu.siacs.conversations.velo;

import androidx.annotation.Nullable;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;

/**
 * Velo 0.21: stories, end-to-end encrypted. A story is a photo or video sent to each chosen
 * contact as an ordinary OMEMO2 file message (so the file is encrypted with its own key and the
 * server only stores ciphertext) that carries a {@code <story/>} payload. With OMEMO2 every
 * payload travels inside the encrypted SCE envelope, so the marker, the expiry and the caption
 * are encrypted too; that is why stories are only ever sent to contacts reached with OMEMO2.
 *
 * <p>Both phones delete the story at its expiry (24 hours), through the timed-message machinery
 * (expireAt, and the sender's retraction).
 */
public final class VeloStory {

    public static final String NAMESPACE = "urn:velo:story:0";
    public static final String ELEMENT = "story";
    public static final long LIFETIME_MS = 24L * 60 * 60 * 1000;

    private VeloStory() {}

    public static Element create(final String id, final long expiresAt, @Nullable final String caption) {
        final Element element = new Element(ELEMENT, NAMESPACE);
        element.setAttribute("id", id);
        element.setAttribute("expires", String.valueOf(expiresAt));
        if (caption != null && !caption.trim().isEmpty()) {
            element.addChild("caption").setContent(caption.trim());
        }
        return element;
    }

    public static boolean isElement(final Element element) {
        return ELEMENT.equals(element.getName()) && NAMESPACE.equals(element.getNamespace());
    }

    @Nullable
    private static Element find(final Message message) {
        for (final Element payload : message.getPayloads()) {
            if (isElement(payload)) {
                return payload;
            }
        }
        return null;
    }

    public static boolean is(final Message message) {
        return find(message) != null;
    }

    @Nullable
    public static String id(final Message message) {
        final Element story = find(message);
        return story == null ? null : story.getAttribute("id");
    }

    @Nullable
    public static String caption(final Message message) {
        final Element story = find(message);
        final Element caption = story == null ? null : story.findChild("caption");
        return caption == null ? null : caption.getContent();
    }

    /** When the story disappears: what the sender asked, never later than a day after it was sent. */
    public static long expiresAt(final Message message) {
        final long latest = message.getTimeSent() + LIFETIME_MS;
        final Element story = find(message);
        if (story == null) {
            return latest;
        }
        try {
            final long asked = Long.parseLong(story.getAttribute("expires"));
            return asked > 0 ? Math.min(asked, latest) : latest;
        } catch (final NumberFormatException e) {
            return latest;
        }
    }
}
