package eu.siacs.conversations.velo;

import android.os.SystemClock;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.RelativeCornerSize;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;

/** Velo 0.21: creating, listing and opening encrypted stories (see {@link VeloStory}). */
public final class VeloStories {

    public static final int REQUEST_PICK = 0x5701;
    public static final int REQUEST_EDIT = 0x5702;
    public static final int REQUEST_CAMERA = 0x5703;
    public static final int REQUEST_CAMERA_PERMISSION = 0x5704;
    private static final String SEEN = "velo_stories_seen";
    private static final long KEYS_WAIT_MS = 30_000;
    private static final String ME = "me:";

    // Velo 0.22: while a story is being edited (Modifica), the one it replaces and what to prefill
    private static String replaceStoryId = null;
    private static String prefillCaption = null;
    private static Set<String> prefillAudience = null;
    // Velo 0.39.1: the account the story is made from; it only goes to that account's contacts
    private static String storyAccount = null;

    private VeloStories() {}

    /** Velo 0.39.1: the account chosen in the side menu, null for "All accounts". */
    @androidx.annotation.Nullable
    private static Account selected(final XmppActivity activity) {
        return activity instanceof eu.siacs.conversations.ui.ConversationsActivity
                ? ((eu.siacs.conversations.ui.ConversationsActivity) activity).veloSelectedAccount()
                : null;
    }

    /** Velo 0.39.1: the accounts whose stories are shown: the selected one, or all of them. */
    private static List<Account> scope(final XmppConnectionService service,
            @androidx.annotation.Nullable final Account selected) {
        final List<Account> result = new ArrayList<>();
        for (final Account account : service.getAccounts()) {
            if (account.isEnabled() && (selected == null || selected == account)) {
                result.add(account);
            }
        }
        return result;
    }

    /**
     * Velo 0.39.1: the account to act from: the selected one, the only one, or asked. The
     * contacts of two servers are never mixed in one story or one note.
     */
    public static void withAccount(final XmppActivity activity,
            final java.util.function.Consumer<Account> action) {
        final XmppConnectionService service = activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final List<Account> accounts = scope(service, selected(activity));
        if (accounts.isEmpty()) {
            return;
        }
        if (accounts.size() == 1) {
            action.accept(accounts.get(0));
            return;
        }
        final CharSequence[] names = new CharSequence[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            names[i] = accounts.get(i).getJid().getLocal();
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.choose_account)
                .setItems(names, (d, which) -> action.accept(accounts.get(which)))
                .show();
    }

    @androidx.annotation.Nullable
    private static Account storyAccount(final XmppConnectionService service) {
        return storyAccount == null ? null : service.findAccountByUuid(storyAccount);
    }

    private static void resetEdit() {
        replaceStoryId = null;
        prefillCaption = null;
        prefillAudience = null;
    }

    /** Handles the picker and the editor for stories; true when the result was ours. */
    public static boolean onActivityResult(final XmppActivity activity, final int requestCode,
            final int resultCode, final Intent data) {
        if (requestCode == REQUEST_PICK) {
            resetEdit();
            if (resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
                afterPick(activity, data.getData());
            }
            return true;
        }
        if (requestCode == REQUEST_CAMERA) {
            resetEdit();
            final File file = captureFile(activity);
            if (resultCode == android.app.Activity.RESULT_OK && file.length() > 0) {
                startEditor(activity, captureUri(activity));
            }
            return true;
        }
        if (requestCode == REQUEST_EDIT) {
            final Uri edited = resultCode == android.app.Activity.RESULT_OK && data != null
                    ? data.getParcelableExtra(eu.siacs.conversations.medialib.activities.EditActivity.KEY_EDITED_URI)
                    : null;
            // the editor wrote its own copy: the raw camera shot is no longer needed
            final File capture = captureFile(activity);
            if (capture.exists()) {
                capture.delete();
            }
            if (edited != null) {
                compose(activity, edited);
            } else {
                resetEdit();
            }
            return true;
        }
        return false;
    }

    /** A photo goes through the editor first (text, emoji, drawing); a video straight on. */
    private static void afterPick(final XmppActivity activity, final Uri uri) {
        final String mime = activity.getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("video/")) {
            compose(activity, uri);
        } else {
            startEditor(activity, uri);
        }
    }

    private static void startEditor(final XmppActivity activity, final Uri uri) {
        final Intent intent = new Intent(activity, eu.siacs.conversations.medialib.activities.EditActivity.class);
        intent.setData(uri);
        intent.putExtra(eu.siacs.conversations.medialib.activities.EditActivity.KEY_CHAT_NAME,
                activity.getString(R.string.velo_story_new));
        intent.putExtra(eu.siacs.conversations.medialib.activities.EditActivity.KEY_VELO_STORY, true);
        activity.startActivityForResult(intent, REQUEST_EDIT);
    }

    /** Our own active story message with this uuid, or null. */
    public static Message findOwn(final XmppConnectionService service, final String uuid) {
        if (service == null) {
            return null;
        }
        for (final Message message : service.databaseBackend.getActiveVeloStories(service::findConversationByUuid)) {
            if (message.getStatus() != Message.STATUS_RECEIVED && message.getUuid().equals(uuid)) {
                return message;
            }
        }
        return null;
    }

    /** Modifica: the same picture back in the editor, caption and audience prefilled; publishing replaces it. */
    public static void edit(final XmppActivity activity, final Message own) {
        final XmppConnectionService service = activity.xmppConnectionService;
        final String id = VeloStory.id(own);
        if (service == null || id == null) {
            return;
        }
        final File file = service.getFileBackend().getFile(own);
        if (!file.exists()) {
            Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        replaceStoryId = id;
        storyAccount = own.getConversation().getAccount().getUuid();
        prefillCaption = VeloStory.caption(own);
        prefillAudience = new HashSet<>();
        for (final Message message : service.databaseBackend.getActiveVeloStories(service::findConversationByUuid)) {
            if (message.getStatus() != Message.STATUS_RECEIVED && id.equals(VeloStory.id(message))) {
                prefillAudience.add(message.getConversation().getUuid());
            }
        }
        final String mime = own.getMimeType();
        if (mime != null && mime.startsWith("video/")) {
            // Publishing deletes the old story and its file: work on a copy of the video
            final File copy = new File(new File(activity.getCacheDir(), "media"), "velo-story-edit.mp4");
            try {
                copy.getParentFile().mkdirs();
                java.nio.file.Files.copy(file.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (final java.io.IOException | UnsupportedOperationException e) {
                resetEdit();
                Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show();
                return;
            }
            compose(activity, Uri.fromFile(copy));
        } else {
            startEditor(activity, Uri.fromFile(file));
        }
    }

    /** Chats that may receive a story: 1:1, Velo on the other side, OMEMO2 (everything encrypted). */
    public static List<Conversation> recipients(final XmppConnectionService service, final Account account,
            final List<Conversation> excluded) {
        final List<Conversation> result = new ArrayList<>();
        for (final Conversation conversation : service.getConversations()) {
            if (conversation.getAccount() != account
                    || conversation.getMode() != Conversation.MODE_SINGLE
                    || conversation.withSelf()
                    || conversation.isBlocked()
                    || conversation.getStatus() != Conversation.STATUS_AVAILABLE) {
                continue;
            }
            // Velo 0.27: not by presence: a contact offline right now gets the story on coming back
            if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                result.add(conversation);
            } else {
                excluded.add(conversation);
            }
        }
        return result;
    }

    /** Velo 0.36: "Crea storia" offers the camera or the gallery. */
    public static void pick(final XmppActivity activity) {
        withAccount(activity, account -> pick(activity, account));
    }

    public static void pick(final XmppActivity activity, final Account account) {
        storyAccount = account.getUuid();
        final CharSequence[] items = {
                activity.getString(R.string.velo_story_take_photo),
                activity.getString(R.string.velo_story_from_gallery)};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_story_new)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        takePhoto(activity);
                    } else {
                        pickFromGallery(activity);
                    }
                })
                .show();
    }

    private static File captureFile(final XmppActivity activity) {
        return new File(new File(activity.getFilesDir(), "Camera"), "velo_story_capture.jpg");
    }

    private static Uri captureUri(final XmppActivity activity) {
        final File file = captureFile(activity);
        return eu.siacs.conversations.persistance.FileBackend.getUriForFile(activity, file, file.getName());
    }

    private static void takePhoto(final XmppActivity activity) {
        if (activity.checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[] {android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        final File file = captureFile(activity);
        file.getParentFile().mkdirs();
        if (file.exists()) {
            file.delete();
        }
        final Uri uri = captureUri(activity);
        final Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_CAMERA);
        } catch (final android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.velo_story_no_camera, Toast.LENGTH_LONG).show();
        }
    }

    /** The camera permission answer for {@link #takePhoto}; true when the request was ours. */
    public static boolean onRequestPermissionsResult(final XmppActivity activity,
            final int requestCode, final int[] grantResults) {
        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return false;
        }
        if (grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            takePhoto(activity);
        } else {
            Toast.makeText(activity, R.string.velo_story_camera_denied, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private static void pickFromGallery(final XmppActivity activity) {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        activity.startActivityForResult(intent, REQUEST_PICK);
    }

    /** After the picker: caption and audience, then one encrypted file message per contact. */
    public static void compose(final XmppActivity activity, final Uri uri) {
        final XmppConnectionService service = activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account account = storyAccount(service);
        if (account == null) {
            // Android closed Velo while the camera or the gallery was open: ask again
            withAccount(activity, chosen -> {
                storyAccount = chosen.getUuid();
                compose(activity, uri);
            });
            return;
        }
        final List<Conversation> excluded = new ArrayList<>();
        final List<Conversation> recipients = recipients(service, account, excluded);
        if (recipients.isEmpty()) {
            Toast.makeText(activity, R.string.velo_story_nobody, Toast.LENGTH_LONG).show();
            return;
        }
        final float density = activity.getResources().getDisplayMetrics().density;
        final int pad = Math.round(20 * density);
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText caption = new EditText(activity);
        caption.setHint(R.string.velo_story_caption);
        if (prefillCaption != null) {
            caption.setText(prefillCaption);
        }
        box.addView(caption);
        final TextView who = new TextView(activity);
        who.setText(R.string.velo_story_who);
        who.setPadding(0, pad / 2, 0, 0);
        box.addView(who);
        final List<MaterialCheckBox> checks = new ArrayList<>();
        for (final Conversation conversation : recipients) {
            final MaterialCheckBox check = new MaterialCheckBox(activity);
            check.setText(conversation.getName());
            check.setChecked(prefillAudience == null || prefillAudience.contains(conversation.getUuid()));
            checks.add(check);
            box.addView(check);
        }
        if (!excluded.isEmpty()) {
            final StringBuilder names = new StringBuilder();
            for (final Conversation conversation : excluded) {
                if (names.length() > 0) names.append(", ");
                names.append(conversation.getName());
            }
            final TextView note = new TextView(activity);
            note.setText(activity.getString(R.string.velo_story_excluded, names));
            note.setPadding(0, pad / 2, 0, 0);
            note.setAlpha(0.7f);
            box.addView(note);
        }
        final ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.velo_story_new)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, (dialog, which) -> resetEdit())
                .setPositiveButton(R.string.velo_story_publish, (dialog, which) -> {
                    final List<Conversation> chosen = new ArrayList<>();
                    for (int i = 0; i < checks.size(); i++) {
                        if (checks.get(i).isChecked()) {
                            chosen.add(recipients.get(i));
                        }
                    }
                    send(activity, uri, caption.getText().toString(), chosen);
                })
                .setOnDismissListener(dialog -> {
                    // Velo 0.28: Modifica starts in the viewer, which has to outlive this dialog
                    if (activity instanceof it.velo.chat.MediaViewerActivity) {
                        activity.finish();
                    }
                })
                .show();
    }

    private static void send(final XmppActivity activity, final Uri uri, final String caption,
            final List<Conversation> chosen) {
        final XmppConnectionService service = activity.xmppConnectionService;
        final String replaced = replaceStoryId;
        resetEdit();
        if (service == null || chosen.isEmpty()) {
            return;
        }
        if (replaced != null) {
            // Modifica: the old story goes from everyone, the new one takes its place
            service.deleteVeloStory(replaced);
        }
        final String id = UUID.randomUUID().toString();
        final long expires = System.currentTimeMillis() + VeloStory.LIFETIME_MS;
        final String mime = activity.getContentResolver().getType(uri);
        final boolean video = mime != null && mime.startsWith("video/");
        activity.delegateUriPermissionsToService(uri);
        final UiCallback<Message> callback = new UiCallback<>() {
            @Override
            public void success(final Message message) {}

            @Override
            public void error(final int errorCode, final Message message) {
                activity.runOnUiThread(() -> Toast.makeText(activity, errorCode, Toast.LENGTH_LONG).show());
            }

            @Override
            public void userInputRequired(final android.app.PendingIntent pi, final Message message) {}
        };
        new Thread(() -> {
            awaitKeys(chosen);
            for (final Conversation conversation : chosen) {
                conversation.setVeloStoryPending(VeloStory.create(id, expires, caption), expires);
                if (video) {
                    service.attachFileToConversation(conversation, uri, mime, null, callback);
                } else {
                    service.attachImageToConversation(conversation, uri, mime, null, callback);
                }
            }
        }, "velo-story").start();
        Toast.makeText(activity, activity.getString(R.string.velo_story_sent, chosen.size()), Toast.LENGTH_LONG).show();
    }

    /**
     * Velo 0.27: sent now, a contact's new device could never read the story. Waits for its keys;
     * past KEYS_WAIT_MS the story goes to the devices already known rather than to nobody.
     */
    private static void awaitKeys(final List<Conversation> chosen) {
        final long deadline = SystemClock.elapsedRealtime() + KEYS_WAIT_MS;
        final CountDownLatch lists = new CountDownLatch(chosen.size());
        for (final Conversation conversation : chosen) {
            final AxolotlService axolotl = conversation.getAccount().getAxolotlService();
            if (axolotl == null) {
                lists.countDown();
            } else {
                axolotl.refreshOmemo2Sessions(conversation, lists::countDown);
            }
        }
        try {
            lists.await(KEYS_WAIT_MS, TimeUnit.MILLISECONDS);
            for (final Conversation conversation : chosen) {
                final AxolotlService axolotl = conversation.getAccount().getAxolotlService();
                while (axolotl != null
                        && SystemClock.elapsedRealtime() < deadline
                        && axolotl.hasPendingKeyFetches(
                                axolotl.getCryptoTargets(conversation), Message.ENCRYPTION_AXOLOTL_OMEMO2)) {
                    Thread.sleep(500);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Velo 0.28: all the active stories of each chat, oldest first within the chat, newest chat
     * first; the own ones, under the key "me:" + account, before them all. A story sent to several contacts
     * is several messages, the own ones count once.
     */
    public static Map<String, List<Message>> active(final XmppConnectionService service) {
        final Map<String, List<Message>> byChat = new LinkedHashMap<>();
        final Map<String, List<Message>> own = new LinkedHashMap<>();
        final Set<String> ids = new HashSet<>();
        for (final Message message : service.databaseBackend.getActiveVeloStories(service::findConversationByUuid)) {
            if (!ids.add(message.getStatus() == Message.STATUS_RECEIVED ? message.getUuid() : String.valueOf(VeloStory.id(message)))) {
                continue;
            }
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                byChat.computeIfAbsent(message.getConversation().getUuid(), key -> new ArrayList<>()).add(0, message);
            } else {
                // Velo 0.39.1: own stories per account, under "me:" + the account
                own.computeIfAbsent(ME + message.getConversation().getAccount().getUuid(),
                        key -> new ArrayList<>()).add(0, message);
            }
        }
        if (own.isEmpty()) {
            return byChat;
        }
        final Map<String, List<Message>> ownFirst = new LinkedHashMap<>(own);
        ownFirst.putAll(byChat);
        return ownFirst;
    }

    public static boolean seen(final android.content.Context context, final Message message) {
        return prefs(context).getStringSet(SEEN, new HashSet<>()).contains(message.getUuid());
    }

    public static void markSeen(final android.content.Context context, final String uuid) {
        final Set<String> seen = new HashSet<>(prefs(context).getStringSet(SEEN, new HashSet<>()));
        seen.add(uuid);
        prefs(context).edit().putStringSet(SEEN, seen).apply();
    }

    private static SharedPreferences prefs(final android.content.Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static void open(final XmppActivity activity, final Message message) {
        open(activity, java.util.Collections.singletonList(message));
    }

    /** Velo 0.28: the stories one after the other, from the first not seen yet; what is still to download is left out. */
    private static void open(final XmppActivity activity, final List<Message> stories) {
        final XmppConnectionService service = activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final ArrayList<String> files = new ArrayList<>();
        final ArrayList<String> mimes = new ArrayList<>();
        final ArrayList<String> uuids = new ArrayList<>();
        final ArrayList<String> captions = new ArrayList<>();
        final boolean own = stories.get(0).getStatus() != Message.STATUS_RECEIVED;
        boolean downloading = false;
        int start = -1;
        for (final Message message : stories) {
            final File file = service.getFileBackend().getFile(message);
            if (!file.exists()) {
                if (!own) {
                    downloading = true;
                    ConversationFragment.downloadFile(activity, message);
                }
                continue;
            }
            if (start < 0 && !own && !seen(activity, message)) {
                start = files.size();
            }
            final String mime = message.getMimeType();
            files.add(file.getAbsolutePath());
            mimes.add(mime != null && mime.startsWith("video/") ? "video/*" : "image/*");
            uuids.add(message.getUuid());
            final String caption = VeloStory.caption(message);
            captions.add(caption == null ? "" : caption);
        }
        if (downloading) {
            Toast.makeText(activity, R.string.velo_story_downloading, Toast.LENGTH_SHORT).show();
        }
        if (files.isEmpty()) {
            if (!downloading) {
                Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final Intent intent = new Intent(activity, it.velo.chat.MediaViewerActivity.class);
        // No conversation or message uuid: the viewer shows only these stories
        intent.putExtra(it.velo.chat.MediaViewerActivity.EXTRA_VELO_STORY, true);
        intent.putExtra(it.velo.chat.MediaViewerActivity.EXTRA_VELO_STORY_OWN, own);
        intent.putStringArrayListExtra(it.velo.chat.MediaViewerActivity.EXTRA_VELO_STORY_FILES, files);
        intent.putStringArrayListExtra(it.velo.chat.MediaViewerActivity.EXTRA_VELO_STORY_MIMES, mimes);
        intent.putStringArrayListExtra(it.velo.chat.MediaViewerActivity.EXTRA_VELO_STORY_UUIDS, uuids);
        intent.putStringArrayListExtra(it.velo.chat.MediaViewerActivity.EXTRA_VELO_STORY_CAPTIONS, captions);
        intent.putExtra(it.velo.chat.MediaViewerActivity.EXTRA_VELO_STORY_START, Math.max(start, 0));
        activity.startActivity(intent);
    }

    /** Fills the row of circles above the chat list; hides it when there is nothing to show. */
    private static LinearLayout storyCell(final XmppActivity activity, final float density) {
        final LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(Math.round(6 * density), Math.round(2 * density), Math.round(6 * density), Math.round(10 * density));
        return cell;
    }

    private static ShapeableImageView storyAvatar(final XmppActivity activity, final float density, final int barColour) {
        final ShapeableImageView avatar = new ShapeableImageView(activity);
        avatar.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(new RelativeCornerSize(0.5f)).build());
        // The bar's colour between the ring and the picture
        final int gap = Math.round(3 * density);
        avatar.setStrokeWidth(gap);
        avatar.setStrokeColor(android.content.res.ColorStateList.valueOf(barColour));
        avatar.setPadding(gap / 2, gap / 2, gap / 2, gap / 2);
        return avatar;
    }

    /** The ring goes on a holder: ShapeableImageView clips away its own background. */
    private static android.widget.FrameLayout storyRing(final XmppActivity activity, final ShapeableImageView avatar,
            final float density, final int size, final boolean unseen, @androidx.annotation.Nullable final View.OnClickListener add) {
        final android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
        final android.graphics.drawable.GradientDrawable halo = new android.graphics.drawable.GradientDrawable();
        halo.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (unseen) {
            halo.setGradientType(android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT);
            halo.setColors(new int[] {0xFFFFD54A, 0xFFFF6FB1, 0xFF8E7BFF, 0xFF5CE1E6, 0xFFFFD54A});
        } else {
            halo.setColor(0x99FFFFFF);
        }
        holder.setBackground(halo);
        final int ring = Math.round(3 * density);
        holder.setPadding(ring, ring, ring, ring);
        holder.setClipToPadding(false);
        holder.addView(avatar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        if (add != null) {
            final android.widget.ImageView plus = new android.widget.ImageView(activity);
            plus.setImageResource(R.drawable.ic_add_24dp);
            plus.setColorFilter(0xFF16233A);
            plus.setBackgroundResource(R.drawable.velo_dot);
            final int inset = Math.round(3 * density);
            plus.setPadding(inset, inset, inset, inset);
            final int badge = Math.round(22 * density);
            // The finger needs more room than the badge shows
            final android.widget.FrameLayout target = new android.widget.FrameLayout(activity);
            target.addView(plus, new android.widget.FrameLayout.LayoutParams(badge, badge, Gravity.BOTTOM | Gravity.END));
            target.setOnClickListener(add);
            target.setContentDescription(activity.getString(R.string.velo_story_new));
            final int room = Math.round(30 * density);
            holder.addView(target, new android.widget.FrameLayout.LayoutParams(room, room, Gravity.BOTTOM | Gravity.END));
        }
        holder.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return holder;
    }

    private static TextView storyName(final XmppActivity activity, final float density, final int colour, final String text) {
        final TextView name = new TextView(activity);
        name.setText(text);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(Math.round(86 * density));
        name.setTextSize(12);
        name.setTextColor(colour);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Math.round(4 * density), 0, 0);
        name.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return name;
    }

    public static void fill(final XmppActivity activity, final View scroll, final LinearLayout row) {
        final XmppConnectionService service = activity.xmppConnectionService;
        row.removeAllViews();
        final List<Account> accounts = service == null ? new ArrayList<>() : scope(service, selected(activity));
        if (accounts.isEmpty()) {
            scroll.setVisibility(View.GONE);
            return;
        }
        final Map<String, List<Message>> stories = active(service);
        final float density = activity.getResources().getDisplayMetrics().density;
        final int size = Math.round(62 * density);
        final int barColour = VeloUi.barColor(activity);
        final int barText = VeloThemes.onColor(barColour);
        // Velo 0.28: "La mia storia" is always first; with no story yet it adds one.
        // Velo 0.39.1: one for each account shown, with its own picture and, if more than one, its name
        final Set<String> shown = new HashSet<>();
        for (final Account account : accounts) {
            shown.add(account.getUuid());
            final String mine = accounts.size() == 1
                    ? activity.getString(R.string.velo_story_mine)
                    : account.getJid().getLocal();
            final List<Message> messages = stories.get(ME + account.getUuid());
            final LinearLayout cell = storyCell(activity, density);
            final ShapeableImageView avatar = storyAvatar(activity, density, barColour);
            AvatarWorkerTask.loadAvatar(account, avatar, R.dimen.muc_avatar_actionbar);
            cell.addView(storyRing(activity, avatar, density, size, false, v -> pick(activity, account)));
            cell.addView(storyName(activity, density, barText, mine));
            if (messages == null) {
                cell.setOnClickListener(v -> pick(activity, account));
            } else {
                cell.setOnClickListener(v -> open(activity, messages));
            }
            row.addView(cell);
        }
        for (final Map.Entry<String, List<Message>> entry : stories.entrySet()) {
            if (entry.getKey().startsWith(ME)) {
                continue;
            }
            final List<Message> messages = entry.getValue();
            final Conversation conversation = (Conversation) messages.get(0).getConversation();
            if (!shown.contains(conversation.getAccount().getUuid())) {
                continue;
            }
            final LinearLayout cell = storyCell(activity, density);
            final ShapeableImageView avatar = storyAvatar(activity, density, barColour);
            boolean unseen = false;
            for (final Message message : messages) {
                unseen |= !seen(activity, message);
            }
            AvatarWorkerTask.loadAvatar(conversation, avatar, R.dimen.muc_avatar_actionbar);
            cell.addView(storyRing(activity, avatar, density, size, unseen, null));
            cell.addView(storyName(activity, density, barText, conversation.getName().toString()));
            cell.setOnClickListener(v -> open(activity, messages));
            row.addView(cell);
        }
        scroll.setVisibility(View.VISIBLE);
    }
}
