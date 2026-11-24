package eu.siacs.conversations.velo;

import android.content.Context;
import android.text.format.DateUtils;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.UIHelper;

/**
 * Velo 0.50, "Presa visione": a message that asks to be confirmed as read. It is an ordinary
 * encrypted message starting with 📋; whoever reads it taps "Confermo di aver letto" and Velo sends
 * an encrypted reply to that message starting with ✅. The sender sees under the message who
 * confirmed and when. Nothing new goes over the wire, so it works in groups and with other apps
 * too (there it reads as a quote with ✅).
 *
 * <p>Not "signed": OMEMO is deniable by design, a confirmation proves nothing to third parties.
 */
public final class VeloAck {

    public static final String MARK = "📋 ";
    static final String DONE = "✅";

    private VeloAck() {}

    /** The text to send for a request, or null when there is nothing to send. */
    public static String compose(final String text) {
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.startsWith(MARK.trim()) ? t : MARK + t;
    }

    /** Pure: whether a body (quotes removed) asks for a confirmation. */
    static boolean isRequestBody(final String body) {
        return body != null && body.startsWith(MARK) && body.length() > MARK.length();
    }

    /** Pure: whether a reply body (quotes removed) is a confirmation. */
    static boolean isConfirmationBody(final String body) {
        return body != null && body.trim().startsWith(DONE);
    }

    public static boolean isRequest(final Message message) {
        return message != null && message.getType() == Message.TYPE_TEXT
                && message.getConversation() instanceof Conversation
                && isRequestBody(message.getBody(true));
    }

    /** The confirmations of a request, oldest first. */
    public static List<Message> confirmations(final Message request) {
        final List<Message> result = new ArrayList<>();
        if (!(request.getConversation() instanceof Conversation conversation)) {
            return result;
        }
        for (final Message reply : conversation.findReplies(request.replyId())) {
            if (reply.getType() == Message.TYPE_TEXT && isConfirmationBody(reply.getBody(true))) {
                result.add(reply);
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.getTimeSent(), b.getTimeSent()));
        return result;
    }

    /** What to write under the request; null hides the line. */
    public static String line(final Context context, final Message request) {
        final List<Message> all = confirmations(request);
        if (request.getStatus() == Message.STATUS_RECEIVED) {
            for (final Message confirmation : all) {
                if (confirmation.getStatus() != Message.STATUS_RECEIVED) {
                    return context.getString(R.string.velo_ack_you_confirmed, when(context, confirmation));
                }
            }
            return context.getString(R.string.velo_ack_confirm);
        }
        final List<String> who = new ArrayList<>();
        for (final Message confirmation : all) {
            if (confirmation.getStatus() == Message.STATUS_RECEIVED) {
                who.add(context.getString(R.string.velo_ack_who_when,
                        UIHelper.getMessageDisplayName(confirmation), when(context, confirmation)));
            }
        }
        if (who.isEmpty()) {
            return context.getString(R.string.velo_ack_waiting);
        }
        return context.getString(R.string.velo_ack_confirmed_by, android.text.TextUtils.join(", ", who));
    }

    /** Whether tapping the line sends a confirmation. */
    public static boolean canConfirm(final Message request) {
        if (request.getStatus() != Message.STATUS_RECEIVED) {
            return false;
        }
        for (final Message confirmation : confirmations(request)) {
            if (confirmation.getStatus() != Message.STATUS_RECEIVED) {
                return false;
            }
        }
        return true;
    }

    public static void askAndConfirm(final XmppActivity activity, final Message request) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_ack)
                .setMessage(R.string.velo_ack_confirm_question)
                .setPositiveButton(R.string.velo_ack_confirm_button, (d, w) -> confirm(activity, request))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void confirm(final XmppActivity activity, final Message request) {
        if (!(request.getConversation() instanceof Conversation conversation)
                || activity.xmppConnectionService == null) {
            return;
        }
        // Velo never sends plaintext: no confirmation outside the encrypted chat
        if (conversation.getNextEncryption() != Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            Toast.makeText(activity, R.string.velo_ack_not_encrypted, Toast.LENGTH_LONG).show();
            return;
        }
        final Message confirmation = request.reply();
        confirmation.appendBody(DONE + " " + activity.getString(R.string.velo_ack_done_text));
        confirmation.setEncryption(Message.ENCRYPTION_AXOLOTL_OMEMO2);
        Message.configurePrivateMessage(confirmation);
        activity.xmppConnectionService.sendMessage(confirmation);
    }

    private static String when(final Context context, final Message message) {
        return DateUtils.formatDateTime(context, message.getTimeSent(),
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }
}
