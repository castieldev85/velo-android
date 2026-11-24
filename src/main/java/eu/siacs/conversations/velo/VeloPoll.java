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
