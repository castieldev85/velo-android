package eu.siacs.conversations.velo;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;

/**
 * Velo: photos and videos that can be opened once. The message carries a {@code <view-once/>}
 * payload, which with OMEMO2 travels inside the encrypted envelope.
 *
 * <p>A Velo receiving it shows no preview, only "open once"; the full-screen viewer has no
 * share/save/open-with, and closing it deletes the file and the message. It never appears in the
 * media gallery. The sender keeps a placeholder and its copy of the file is wiped after a day.
 *
 * <p>Apps that don't know it (Monal...) would show a normal photo: there the message gets a
 * short timer, and the sender retracts it when it runs out (see VeloRetractionQueue).
 */
public final class VeloViewOnce {

    public static final String NAMESPACE = "urn:velo:view-once:0";
    public static final String ELEMENT = "view-once";

    /** How long a non-Velo peer gets the photo before it is retracted. */
    public static final int NON_VELO_SECONDS = 60;

    /** How long the sender keeps its own copy. */
    public static final long SENDER_KEEP_MS = 24L * 60 * 60 * 1000;

    private VeloViewOnce() {}

    public static Element create() {
        return create(0);
    }

    /**
     * Velo 0.18: {@code seconds} > 0 keeps the photo open that long after it is opened, then it
     * closes and is deleted (as in Telegram); 0 is a single view. Older Velo ignore the attribute
     * and treat it as a single view.
     */
    public static Element create(final int seconds) {
        final Element element = new Element(ELEMENT, NAMESPACE);
        if (seconds > 0) {
            element.setAttribute("seconds", String.valueOf(seconds));
        }
        return element;
    }

    /** The seconds a view-once photo stays open after opening; 0 for a single view. */
    public static int seconds(final Message message) {
        for (final Element payload : message.getPayloads()) {
            if (isElement(payload)) {
                try {
                    final String value = payload.getAttribute("seconds");
                    return value == null ? 0 : Math.max(0, Math.min(Integer.parseInt(value), 60));
                } catch (final NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** The choices of the timer on an attached photo: single view, then seconds after opening. */
    public static final int[] CHOICES = {0, 3, 10, 30};

    public static boolean isElement(final Element element) {
        return ELEMENT.equals(element.getName()) && NAMESPACE.equals(element.getNamespace());
    }

    public static boolean is(final Message message) {
        for (final Element payload : message.getPayloads()) {
            if (isElement(payload)) {
                return true;
            }
        }
        return false;
    }
}
