package eu.siacs.conversations.velo;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;

/** Velo 0.50: the settings dialogs of "Messaggio se non torno" ({@link VeloComeback}). */
public final class VeloComebackUi {

    private VeloComebackUi() {}

    public static String summary(final Context context) {
        final int days = VeloComeback.days(context);
        if (days <= 0) {
            return context.getString(R.string.velo_comeback_off);
        }
        return context.getString(R.string.velo_comeback_on, days, contactName(context));
    }

    public static void show(final Activity activity, final Runnable changed) {
        if (VeloComeback.days(activity) <= 0) {
            explain(activity, changed);
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_comeback_title)
                .setMessage(summary(activity) + "\n\n“" + VeloComeback.text(activity) + "”")
                .setPositiveButton(R.string.velo_comeback_change, (d, w) -> chooseDays(activity, changed))
                .setNegativeButton(R.string.velo_comeback_turn_off, (d, w) -> {
                    VeloComeback.disable(activity);
                    changed.run();
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    private static void explain(final Activity activity, final Runnable changed) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_comeback_title)
                .setMessage(R.string.velo_comeback_explanation)
                .setPositiveButton(R.string.velo_comeback_set_up, (d, w) -> chooseDays(activity, changed))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void chooseDays(final Activity activity, final Runnable changed) {
        final int[] choices = VeloComeback.CHOICES;
        final List<Integer> days = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (final int choice : choices) {
            if (choice > 0) {
                days.add(choice);
                names.add(activity.getString(R.string.velo_autowipe_days, choice));
            }
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_comeback_after)
                .setItems(names.toArray(new String[0]), (d, which) -> chooseContact(activity, days.get(which), changed))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void chooseContact(final Activity activity, final int days, final Runnable changed) {
        final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
        final List<Contact> contacts = new ArrayList<>();
        if (service != null) {
            for (final Account account : service.getAccounts()) {
                for (final Contact contact : account.getRoster().getContacts()) {
                    if (contact.showInContactList() && !contact.isSelf()) {
                        contacts.add(contact);
                    }
                }
            }
        }
        if (contacts.isEmpty()) {
            Toast.makeText(activity, R.string.velo_comeback_no_contacts, Toast.LENGTH_LONG).show();
            return;
        }
        Collections.sort(contacts, (a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        final String[] names = new String[contacts.size()];
        final boolean manyAccounts = service.getAccounts().size() > 1;
        for (int i = 0; i < names.length; i++) {
            final Contact contact = contacts.get(i);
            // with more profiles on the phone the same person can be there twice: say from which
            names[i] = manyAccounts
                    ? activity.getString(R.string.velo_comeback_contact_from, contact.getDisplayName(),
                            VeloNames.of(contact.getAccount()))
                    : contact.getDisplayName();
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_comeback_who)
                .setItems(names, (d, which) -> writeText(activity, days, contacts.get(which), changed))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void writeText(final Activity activity, final int days, final Contact contact,
            final Runnable changed) {
        final EditText text = new EditText(activity);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final String previous = VeloComeback.text(activity);
        text.setText(previous.isEmpty() ? activity.getString(R.string.velo_comeback_default_text, days) : previous);
        final FrameLayout box = new FrameLayout(activity);
        final int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(text);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.velo_comeback_text_for, contact.getDisplayName()))
                .setView(box)
                .setPositiveButton(R.string.velo_comeback_save, (d, w) -> {
                    final String message = text.getText().toString().trim();
                    if (message.isEmpty()) {
                        Toast.makeText(activity, R.string.velo_comeback_empty, Toast.LENGTH_LONG).show();
                        return;
                    }
                    VeloComeback.enable(activity, days, contact.getAccount(), contact.getJid(), message);
                    changed.run();
                    Toast.makeText(activity, summary(activity), Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String contactName(final Context context) {
        final String jid = VeloComeback.contact(context);
        final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
        if (jid != null && service != null) {
            for (final Account account : service.getAccounts()) {
                for (final Contact contact : account.getRoster().getContacts()) {
                    if (contact.getJid().asBareJid().toString().equals(jid)) {
                        return contact.getDisplayName();
                    }
                }
            }
        }
        // never the domain on screen
        return jid == null ? "?" : jid.split("@")[0];
    }
}
