package eu.siacs.conversations.velo;

import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import eu.siacs.conversations.R;

/**
 * Velo 0.50: a poll is an ordinary encrypted message, the question and the choices numbered
 * 1️⃣ 2️⃣ 3️⃣…; people vote with the reaction of their number, which Velo already counts under
 * the message. Nothing new goes over the wire, so it works in groups, in 1:1 chats and with
 * other apps too.
 */
public final class VeloPoll {

    public static final String[] NUMBERS = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣"};

    private VeloPoll() {}

    public static void show(final Context context, final Consumer<String> send) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = Math.round(20 * density);
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, 0);
        final EditText question = field(context, context.getString(R.string.velo_poll_question));
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(question);
        final List<EditText> options = new ArrayList<>();
        for (int i = 0; i < NUMBERS.length; i++) {
            final EditText option = field(context, context.getString(R.string.velo_poll_option, i + 1));
            options.add(option);
            box.addView(option);
        }
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.velo_poll)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.velo_poll_send, (d, w) -> {
                    final String text = compose(question.getText().toString(), texts(options));
                    if (text == null) {
                        Toast.makeText(context, R.string.velo_poll_incomplete, Toast.LENGTH_LONG).show();
                    } else {
                        send.accept(text);
                    }
                })
                .show();
    }

    /** The message text, or null without a question or with fewer than two choices. */
    public static String compose(final String question, final List<String> options) {
        final String q = question == null ? "" : question.trim();
        final List<String> filled = new ArrayList<>();
        for (final String option : options) {
            if (option != null && !option.trim().isEmpty() && filled.size() < NUMBERS.length) {
                filled.add(option.trim());
            }
        }
        if (q.isEmpty() || filled.size() < 2) {
            return null;
        }
        final StringBuilder text = new StringBuilder("📊 ").append(q).append('\n');
        for (int i = 0; i < filled.size(); i++) {
            text.append('\n').append(NUMBERS[i]).append(' ').append(filled.get(i));
        }
        return text.toString();
    }

    /** The numbers of a poll's choices, or the usual reactions for any other message. */
    public static String[] reactionsFor(final String body, final String[] usual) {
        if (body == null || !body.startsWith("📊 ")) {
            return usual;
        }
        final List<String> found = new ArrayList<>();
        for (final String line : body.split("\n")) {
            for (final String number : NUMBERS) {
                if (line.startsWith(number + " ") && !found.contains(number)) {
                    found.add(number);
                }
            }
        }
        return found.size() < 2 ? usual : found.toArray(new String[0]);
    }

    // ------------------------------------------------------------ Velo 0.51: poll with buttons

    /** A poll read back from its message: the question and the choices, in order. */
    public static final class Poll {
        public final String question;
        public final List<String> options;

        Poll(final String question, final List<String> options) {
            this.question = question;
            this.options = options;
        }
    }

    /**
     * The poll in a message body, or null when it is no poll (no "📊 ", or fewer than two
     * numbered choices). The choices must be 1️⃣ 2️⃣ … in order, as {@link #compose} writes them.
     */
    @androidx.annotation.Nullable
    public static Poll parse(final String body) {
        if (body == null || !body.startsWith("📊 ")) {
            return null;
        }
        final StringBuilder question = new StringBuilder();
        final List<String> options = new ArrayList<>();
        for (final String line : body.substring("📊 ".length()).split("\n")) {
            final int next = options.size();
            if (next < NUMBERS.length && line.startsWith(NUMBERS[next] + " ")) {
                options.add(line.substring(NUMBERS[next].length() + 1).trim());
            } else if (options.isEmpty()) {
                if (question.length() > 0 || !line.trim().isEmpty()) {
                    question.append(question.length() > 0 ? "\n" : "").append(line);
                }
            } else if (!line.trim().isEmpty()) {
                return null; // text after the choices: not a poll Velo wrote
            }
        }
        final String q = question.toString().trim();
        return q.isEmpty() || options.size() < 2 ? null : new Poll(q, options);
    }

    /** The choice an emoji votes for (0-based), or -1. Other apps may drop the U+FE0F. */
    public static int choiceOf(final String emoji) {
        if (emoji == null) {
            return -1;
        }
        final String key = emoji.replace("️", "");
        for (int i = 0; i < NUMBERS.length; i++) {
            if (NUMBERS[i].replace("️", "").equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Our reactions after tapping a choice: one vote only, so any other number goes; tapping the
     * choice we already voted takes the vote back. Our other reactions (❤️…) stay.
     */
    public static java.util.Set<String> vote(final java.util.Collection<String> ours, final int choice) {
        final java.util.Set<String> result = new java.util.LinkedHashSet<>();
        boolean had = false;
        for (final String reaction : ours) {
            final int c = choiceOf(reaction);
            if (c < 0) {
                result.add(reaction);
            } else if (c == choice) {
                had = true;
            }
        }
        if (!had) {
            result.add(NUMBERS[choice]);
        }
        return result;
    }

    /** Our current vote (0-based), or -1. */
    public static int ourVote(final java.util.Collection<String> ours) {
        for (final String reaction : ours) {
            final int c = choiceOf(reaction);
            if (c >= 0) {
                return c;
            }
        }
        return -1;
    }

    /** Percent of all votes, rounded; 0 with no votes. */
    public static int percent(final int votes, final int total) {
        return total <= 0 ? 0 : Math.round(votes * 100f / total);
    }

    private static List<String> texts(final List<EditText> fields) {
        final List<String> out = new ArrayList<>();
        for (final EditText field : fields) {
            out.add(field.getText().toString());
        }
        return out;
    }

    private static EditText field(final Context context, final String hint) {
        final EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }
}
