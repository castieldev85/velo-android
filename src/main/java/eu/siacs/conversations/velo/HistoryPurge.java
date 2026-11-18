package eu.siacs.conversations.velo;

import androidx.annotation.Nullable;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;

/**
 * Velo: "delete history for both". The sender clears a 1:1 chat locally and sends the peer a
 * control message carrying the clear time; the peer deletes every message of that chat sent up
 * to that time.
 *
 * <p>With OMEMO2 the {@code <purge/>} element travels as a payload inside the encrypted SCE
 * envelope. Legacy OMEMO can only encrypt the body, so there the element rides on the outer
 * stanza and is only honoured when the decrypted body is {@link #FALLBACK_BODY}: only the
 * sender can produce that ciphertext, which authenticates the request. Unencrypted purges are
 * ignored.
 *
 * <p>Other apps (Monal...) don't know the purge and only show {@link #FALLBACK_BODY}; for them
 * the sender also retracts its own messages one by one (XEP-0424), which they do honour. Their
 * own messages stay: a retraction only works for the one who wrote the message.
 */
public final class HistoryPurge {

    public static final String NAMESPACE = "urn:velo:history-purge:0";
    public static final String ELEMENT = "purge";
    public static final String FALLBACK_BODY = "Ho cancellato la cronologia di questa chat.";
    /** Sent by Velo before 0.12; still accepted from older Velos. */
    private static final String OLD_FALLBACK_BODY = "The chat history has been deleted by the sender.";
    private static final String BEFORE = "before";

    private HistoryPurge() {}

    public static Element create(final long before) {
        final Element purge = new Element(ELEMENT, NAMESPACE);
        purge.setAttribute(BEFORE, String.valueOf(before));
        return purge;
    }

    public static boolean is(final Element element) {
        return ELEMENT.equals(element.getName()) && NAMESPACE.equals(element.getNamespace());
    }

    /** The clear time of a received purge, or null when this message is not a valid purge. */
    @Nullable
    public static Long extract(final Message message, final Element stanza) {
        final Element purge;
        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            purge = find(message);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                && (FALLBACK_BODY.equals(message.getRawBody())
                        || OLD_FALLBACK_BODY.equals(message.getRawBody()))) {
            purge = stanza.findChild(ELEMENT, NAMESPACE);
        } else {
            return null;
        }
        if (purge == null) {
            return null;
        }
        try {
            return Long.parseLong(purge.getAttribute(BEFORE));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** Whether this outgoing message is a purge we are sending. */
    public static boolean isPurge(final Message message) {
        return find(message) != null;
    }

    @Nullable
    private static Element find(final Message message) {
        for (final Element payload : message.getPayloads()) {
            if (is(payload)) {
                return payload;
            }
        }
        return null;
    }
}
