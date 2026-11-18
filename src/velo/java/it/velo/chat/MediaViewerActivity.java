package it.velo.chat;

import eu.siacs.conversations.ui.util.BackHandler;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import eu.siacs.conversations.velo.Log;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.leinardi.android.speeddial.SpeedDialActionItem;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMediaViewerBinding;
import eu.siacs.conversations.databinding.ItemMediaViewerBinding;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.MediaBrowserActivity;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.Rationals;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.xmpp.Jid;
import me.drakeet.support.toast.ToastCompat;

public class MediaViewerActivity extends XmppActivity implements OnMediaLoaded, AudioManager.OnAudioFocusChangeListener {

    Integer oldOrientation;
    ExoPlayer player;
    File mFile;
    int height = 0;
    int width = 0;
    Rational aspect;
    int rotation = 0;
    boolean isImage = false;
    boolean isVideo = false;
    boolean isAudio = false;
    private ActivityMediaViewerBinding binding;
    private GestureDetector gestureDetector;
    MediaSession mediaSession;

    private MediaPagerAdapter pagerAdapter;
    private final List<Attachment> attachments = new ArrayList<>();
    private String initialMessageUuid;
    private Uri currentMediaUri;

    public static String getMimeType(String path) {
        try {
            String type = null;
            String extension = path.substring(path.lastIndexOf(".") + 1, path.length());
            if (extension != null) {
                type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            }
            return type;
        } catch (Exception e) {
            eu.siacs.conversations.velo.Log.w(eu.siacs.conversations.Config.LOGTAG, e);
        }
        return null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackHandler.register(this, this::handleBack);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_media_viewer);

        // The media are handed to us oldest first, so the pager is laid out the way it reads:
        // swiping to the left moves on to the next, newer file. Do not flip the layout direction
        // to compensate for a reversed list — the files of a multi-file message all share one
        // timestamp, so a reversed list turns them around among themselves.
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    hideFAB();
                } else {
                    if (Compatibility.runsTwentyFour() && isInPictureInPictureMode()) {
                        hideFAB();
                    } else {
                        showFAB();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(Config.LOGTAG, "PlayerError: ", error);
            }
        });

        pagerAdapter = new MediaPagerAdapter();
        binding.viewPager.setAdapter(pagerAdapter);
        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (!attachments.isEmpty() && position < attachments.size()) {
                    onMediaItemSelected(attachments.get(position));
                    veloStorySelected(position);
                }
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (isImage) {
                    if (binding.speedDial.isShown()) {
                        hideFAB();
                    } else {
                        showFAB();
                    }
                }
                return super.onDown(e);
            }
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null && actionBar.isShowing()) {
            actionBar.hide();
        }

        oldOrientation = getRequestedOrientation();

        WindowManager.LayoutParams layout = getWindow().getAttributes();
        if (useMaxBrightness()) {
            layout.screenBrightness = 1;
        }
        getWindow().setAttributes(layout);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        keepSpeedDialClearOfSystemBars();

        initialMessageUuid = getIntent().getStringExtra("message_uuid");
        veloViewOnce = getIntent().getStringExtra(EXTRA_VELO_VIEW_ONCE);
        veloStory = getIntent().getBooleanExtra(EXTRA_VELO_STORY, false);
        veloStoryTaps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(final MotionEvent e) {
                return veloStoryTap(e);
            }
        });
        veloViewSeconds = veloViewOnce == null ? 0 : getIntent().getIntExtra(EXTRA_VELO_VIEW_SECONDS, 0);
        showInitialMediaImmediately();
    }

    /** Velo: uuid of a view-once message; the viewer shows only that, and deletes it when left. */
    public static final String EXTRA_VELO_VIEW_ONCE = "velo_view_once";
    private String veloViewOnce;

    /** Velo 0.21: stories; nothing to share or save, but not deleted on leaving. Velo 0.28: several in a row. */
    public static final String EXTRA_VELO_STORY = "velo_story";
    /** Velo 0.22: our own stories: the viewer offers Modifica and Elimina. */
    public static final String EXTRA_VELO_STORY_OWN = "velo_story_own";
    public static final String EXTRA_VELO_STORY_FILES = "velo_story_files";
    public static final String EXTRA_VELO_STORY_MIMES = "velo_story_mimes";
    public static final String EXTRA_VELO_STORY_UUIDS = "velo_story_uuids";
    public static final String EXTRA_VELO_STORY_CAPTIONS = "velo_story_captions";
    public static final String EXTRA_VELO_STORY_START = "velo_story_start";
    private boolean veloStory = false;
    private final ArrayList<String> veloStoryUuids = new ArrayList<>();
    private final ArrayList<String> veloStoryCaptions = new ArrayList<>();
    private GestureDetector veloStoryTaps;
    private android.widget.LinearLayout veloStoryActions;
    private android.widget.TextView veloStoryCount;
    private android.widget.TextView veloStoryCaption;

    private void showVeloStories(final Intent intent) {
        final ArrayList<String> files = intent.getStringArrayListExtra(EXTRA_VELO_STORY_FILES);
        final ArrayList<String> mimes = intent.getStringArrayListExtra(EXTRA_VELO_STORY_MIMES);
        veloStoryUuids.clear();
        veloStoryCaptions.clear();
        attachments.clear();
        if (files == null || mimes == null) {
            finish();
            return;
        }
        veloStoryUuids.addAll(intent.getStringArrayListExtra(EXTRA_VELO_STORY_UUIDS));
        veloStoryCaptions.addAll(intent.getStringArrayListExtra(EXTRA_VELO_STORY_CAPTIONS));
        for (int i = 0; i < files.size(); i++) {
            attachments.add(Attachment.of(UUID.randomUUID(), new File(files.get(i)), mimes.get(i)));
        }
        addVeloStoryViews();
        veloStoryActions.setVisibility(intent.getBooleanExtra(EXTRA_VELO_STORY_OWN, false) ? View.VISIBLE : View.GONE);
        pagerAdapter.notifyDataSetChanged();
        final int start = intent.getIntExtra(EXTRA_VELO_STORY_START, 0);
        binding.viewPager.setCurrentItem(start, false);
        onMediaItemSelected(attachments.get(start));
        veloStorySelected(start);
    }

    /** What the stories show over the picture: how far along, the caption, Modifica and Elimina. */
    private void addVeloStoryViews() {
        if (veloStoryActions != null) {
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        veloStoryActions = new android.widget.LinearLayout(this);
        veloStoryActions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (final int label : new int[] {eu.siacs.conversations.R.string.velo_story_edit, eu.siacs.conversations.R.string.velo_story_delete}) {
            final com.google.android.material.button.MaterialButton button =
                    new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(label);
            button.setTextColor(android.graphics.Color.WHITE);
            button.setStrokeColor(android.content.res.ColorStateList.valueOf(0x99FFFFFF));
            final android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(Math.round(8 * density));
            veloStoryActions.addView(button, lp);
            button.setOnClickListener(v -> {
                final eu.siacs.conversations.entities.Message own = eu.siacs.conversations.velo.VeloStories.findOwn(
                        xmppConnectionService, veloStoryUuids.get(binding.viewPager.getCurrentItem()));
                if (own == null) {
                    finish();
                    return;
                }
                if (label == eu.siacs.conversations.R.string.velo_story_delete) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(eu.siacs.conversations.R.string.velo_story_delete_title)
                            .setMessage(eu.siacs.conversations.R.string.velo_story_delete_message)
                            .setNegativeButton(eu.siacs.conversations.R.string.cancel, null)
                            .setPositiveButton(eu.siacs.conversations.R.string.velo_story_delete, (d, w) -> {
                                xmppConnectionService.deleteVeloStory(eu.siacs.conversations.velo.VeloStory.id(own));
                                android.widget.Toast.makeText(this, eu.siacs.conversations.R.string.velo_story_deleted, android.widget.Toast.LENGTH_SHORT).show();
                                finish();
                            })
                            .show();
                } else {
                    eu.siacs.conversations.velo.VeloStories.edit(this, own);
                }
            });
        }
        final android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
        params.setMargins(0, Math.round(44 * density), Math.round(12 * density), 0);
        addContentView(veloStoryActions, params);

        final int gap = Math.round(8 * density);
        veloStoryCount = veloStoryLabel();
        final android.widget.FrameLayout.LayoutParams countParams = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.START);
        countParams.setMargins(Math.round(16 * density), Math.round(52 * density), 0, 0);
        addContentView(veloStoryCount, countParams);

        veloStoryCaption = veloStoryLabel();
        veloStoryCaption.setGravity(android.view.Gravity.CENTER);
        final android.widget.FrameLayout.LayoutParams captionParams = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        captionParams.setMargins(gap * 2, 0, gap * 2, Math.round(72 * density));
        addContentView(veloStoryCaption, captionParams);
    }

    private android.widget.TextView veloStoryLabel() {
        final float density = getResources().getDisplayMetrics().density;
        final android.widget.TextView label = new android.widget.TextView(this);
        label.setTextColor(android.graphics.Color.WHITE);
        label.setTextSize(14);
        final android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0x99000000);
        background.setCornerRadius(14 * density);
        label.setBackground(background);
        label.setPadding(Math.round(12 * density), Math.round(5 * density), Math.round(12 * density), Math.round(5 * density));
        return label;
    }

    private void veloStorySelected(final int position) {
        if (!veloStory || position >= veloStoryUuids.size()) {
            return;
        }
        eu.siacs.conversations.velo.VeloStories.markSeen(this, veloStoryUuids.get(position));
        veloStoryCount.setText(getString(eu.siacs.conversations.R.string.velo_story_position, position + 1, veloStoryUuids.size()));
        veloStoryCount.setVisibility(veloStoryUuids.size() > 1 ? View.VISIBLE : View.GONE);
        final String caption = veloStoryCaptions.get(position);
        veloStoryCaption.setText(caption);
        veloStoryCaption.setVisibility(caption.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Touch on the left: the story before. Anywhere else: the next one; after the last, back to the list. */
    private boolean veloStoryTap(final MotionEvent event) {
        if (veloStoryActions != null && veloStoryActions.getVisibility() == View.VISIBLE) {
            // On the screen, not in the window: in a free-form window the two differ
            final int[] origin = new int[2];
            veloStoryActions.getLocationOnScreen(origin);
            final android.graphics.Rect actions = new android.graphics.Rect(origin[0], origin[1],
                    origin[0] + veloStoryActions.getWidth(), origin[1] + veloStoryActions.getHeight());
            if (actions.contains(Math.round(event.getRawX()), Math.round(event.getRawY()))) {
                return false;
            }
        }
        final int current = binding.viewPager.getCurrentItem();
        if (event.getX() < binding.getRoot().getWidth() / 3f) {
            if (current > 0) {
                binding.viewPager.setCurrentItem(current - 1, false);
            }
        } else if (current < attachments.size() - 1) {
            binding.viewPager.setCurrentItem(current + 1, false);
        } else {
            finish();
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        if (veloStory && attachments.size() > 1) {
            veloStoryTaps.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (eu.siacs.conversations.velo.VeloStories.onActivityResult(this, requestCode, resultCode, data)) {
            return; // the dialog of Modifica closes the viewer when it is done
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // Velo 0.18: seconds the photo stays open (0 = until closed); a ring counts them down, then
    // the viewer closes and onStop() deletes the photo
    public static final String EXTRA_VELO_VIEW_SECONDS = "velo_view_seconds";
    private int veloViewSeconds = 0;
    private long veloViewEndsAt = 0;
    private final android.os.Handler veloViewHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private com.google.android.material.progressindicator.CircularProgressIndicator veloViewRing;
    private android.widget.TextView veloViewCount;

    private void startVeloViewCountdown() {
        if (veloViewOnce == null || veloViewSeconds <= 0 || veloViewEndsAt != 0) {
            return;
        }
        veloViewEndsAt = android.os.SystemClock.uptimeMillis() + veloViewSeconds * 1000L;
        final float density = getResources().getDisplayMetrics().density;
        final android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        veloViewRing = new com.google.android.material.progressindicator.CircularProgressIndicator(this);
        veloViewRing.setMax(veloViewSeconds * 1000);
        veloViewRing.setProgress(veloViewSeconds * 1000);
        veloViewRing.setIndicatorSize(Math.round(44 * density));
        veloViewRing.setTrackThickness(Math.round(4 * density));
        veloViewRing.setIndicatorColor(android.graphics.Color.WHITE);
        veloViewRing.setTrackColor(0x55FFFFFF);
        box.addView(veloViewRing, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        veloViewCount = new android.widget.TextView(this);
        veloViewCount.setTextColor(android.graphics.Color.WHITE);
        veloViewCount.setTextSize(14);
        veloViewCount.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(veloViewCount, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        box.setBackgroundResource(eu.siacs.conversations.R.drawable.velo_view_once_chip);
        box.setPadding(Math.round(4 * density), Math.round(4 * density), Math.round(4 * density), Math.round(4 * density));
        final android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
        params.setMargins(0, Math.round(48 * density), Math.round(16 * density), 0);
        addContentView(box, params);
        veloViewHandler.post(veloViewTick);
    }

    private final Runnable veloViewTick = new Runnable() {
        @Override
        public void run() {
            final long left = veloViewEndsAt - android.os.SystemClock.uptimeMillis();
            if (left <= 0) {
                finish(); // onStop() consumes the view-once message
                return;
            }
            veloViewRing.setProgress((int) left);
            veloViewCount.setText(String.valueOf((left + 999) / 1000));
            veloViewHandler.postDelayed(this, 100);
        }
    };

    private void consumeVeloViewOnce() {
        if (veloViewOnce == null) {
            return;
        }
        final var service = xmppConnectionService != null
                ? xmppConnectionService
                : eu.siacs.conversations.ui.XmppActivity.staticXmppConnectionService;
        if (service != null) {
            service.consumeVeloViewOnce(veloViewOnce);
        }
        veloViewOnce = null;
    }

    /**
     * The window is drawn edge to edge, so the speed dial anchored to the bottom end would sit
     * under the navigation buttons (or, in landscape, beside them). Its margins grow by whatever
     * the navigation bar and display cutout take from that corner, on top of the layout's own.
     */
    private void keepSpeedDialClearOfSystemBars() {
        final View speedDial = binding.speedDial;
        final ViewGroup.MarginLayoutParams base =
                (ViewGroup.MarginLayoutParams) speedDial.getLayoutParams();
        final int left = base.leftMargin;
        final int right = base.rightMargin;
        final int bottom = base.bottomMargin;
        // read on the root: below Android 11 the pager in front of it may consume the insets
        // before they are dispatched on to the speed dial
        final View root = binding.getRoot();
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (v, insets) -> {
                    final Insets bars =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.navigationBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    final ViewGroup.MarginLayoutParams lp =
                            (ViewGroup.MarginLayoutParams) speedDial.getLayoutParams();
                    lp.leftMargin = left + bars.left;
                    lp.rightMargin = right + bars.right;
                    lp.bottomMargin = bottom + bars.bottom;
                    speedDial.setLayoutParams(lp);
                    return insets;
                });
        ViewCompat.requestApplyInsets(root);
    }

    private void showInitialMediaImmediately() {
        final Intent intent = getIntent();
        if (veloStory) {
            showVeloStories(intent);
            return;
        }
        Uri uri = null;
        String mime = null;
        if (intent.hasExtra("image")) {
            uri = intent.getParcelableExtra("image");
            mime = "image/*";
        } else if (intent.hasExtra("video")) {
            uri = intent.getParcelableExtra("video");
            mime = "video/*";
        } else if (intent.hasExtra("audio")) {
            uri = intent.getParcelableExtra("audio");
            mime = "audio/*";
        }
        if (uri == null) {
            return;
        }
        UUID uuid;
        try {
            uuid = initialMessageUuid != null ? UUID.fromString(initialMessageUuid) : UUID.randomUUID();
        } catch (final IllegalArgumentException e) {
            uuid = UUID.randomUUID();
        }
        attachments.clear();
        attachments.add(Attachment.of(uuid, new File(uri.getPath()), mime));
        pagerAdapter.notifyDataSetChanged();
        onMediaItemSelected(attachments.get(0));
    }

    private void share() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(getMimeType(mFile.toString()));
        share.putExtra(Intent.EXTRA_STREAM, FileBackend.getUriForFile(this, mFile, mFile.getName()));
        try {
            startActivity(Intent.createChooser(share, getText(R.string.share_with)));
        } catch (ActivityNotFoundException e) {
            ToastCompat.makeText(this, R.string.no_application_found_to_open_file, ToastCompat.LENGTH_SHORT).show();
        }
    }

    /**
     * Deletes the media on the current page the same way the media browser does: the file goes,
     * and its message is marked as having a deleted file (database and loaded conversations), so
     * neither the gallery nor the chat keeps listing it. The pager then moves on to the next media,
     * or closes when none is left.
     */
    private void deleteFile() {
        final int position = binding.viewPager.getCurrentItem();
        if (xmppConnectionService == null || position < 0 || position >= attachments.size()) {
            return;
        }
        final Attachment attachment = attachments.get(position);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_file_dialog)
                .setMessage(R.string.delete_file_dialog_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    final XmppConnectionService service = xmppConnectionService;
                    if (service == null) {
                        return;
                    }
                    new Thread(() -> {
                        final boolean deleted =
                                !service.deleteMedia(Collections.singletonList(attachment)).isEmpty();
                        runOnUiThread(() -> onMediaDeleted(attachment, deleted));
                    }).start();
                })
                .show();
    }

    private void onMediaDeleted(final Attachment attachment, final boolean deleted) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!deleted) {
            ToastCompat.makeText(this, R.string.could_not_delete_file, ToastCompat.LENGTH_SHORT).show();
            return;
        }
        // match by message, not by object: a reload may have replaced the list meanwhile
        int index = -1;
        for (int i = 0; i < attachments.size(); i++) {
            if (attachments.get(i).getUuid().equals(attachment.getUuid())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        attachments.remove(index);
        if (attachments.isEmpty()) {
            finish();
            return;
        }
        pagerAdapter.notifyItemRemoved(index);
        // If the removed media was the one playing, forget it so the next one is loaded.
        if (attachment.getUri().equals(currentMediaUri)) {
            currentMediaUri = null;
        }
        final int next = Math.min(index, attachments.size() - 1);
        if (binding.viewPager.getCurrentItem() != next) {
            binding.viewPager.setCurrentItem(next, false);
        }
        // Removing the current page does not always fire onPageSelected for the page that slides
        // into its place, so pick it up here.
        onMediaItemSelected(attachments.get(next));
    }

    @Override
    protected void refreshUiReal() {
    }

    @Override
    public void onStart() {
        super.onStart();
        updateSpeedDialActions();
    }

    private void saveToDownloads(File file) {
        this.xmppConnectionService.copyAttachmentToDownloadsFolder(file, new UiCallback<>() {
            @Override
            public void success(Integer object) {
                runOnUiThread(() -> Toast.makeText(MediaViewerActivity.this, getString(R.string.save_to_downloads_success), Toast.LENGTH_LONG).show());
            }

            @Override
            public void error(int errorCode, Integer object) {
                runOnUiThread(() -> Toast.makeText(MediaViewerActivity.this, getString(object), Toast.LENGTH_LONG).show());
            }

            @Override
            public void userInputRequired(PendingIntent pi, Integer object) {
            }
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void PIPVideo() {
        try {
            binding.speedDial.setVisibility(View.GONE);
            if (supportsPIP()) {
                if (Compatibility.runsTwentySix()) {
                    final Rational rational = new Rational(width, height);
                    if (rational.getDenominator() == 0) {
                        Log.w(Config.LOGTAG, "Invalid aspect ratio for PIP: width=" + width + ", height=" + height);
                        return;
                    }
                    final Rational clippedRational = Rationals.clip(rational);
                    final PictureInPictureParams params = new PictureInPictureParams.Builder()
                            .setAspectRatio(clippedRational)
                            .build();
                    this.enterPictureInPictureMode(params);
                } else {
                    this.enterPictureInPictureMode();
                }
            }
        } catch (final IllegalStateException e) {
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e);
        } catch (final IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "Illegal argument for picture in picture mode (aspect ratio?): " + e.getMessage());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            player.play();
            hideFAB();
        } else {
            showFAB();
        }
    }

    private void abandonAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.abandonAudioFocus(this);
            hasAudioFocus = false;
        }
    }

    private void requestAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }
    }

    private boolean handleBack() {
        if (isVideo && isPlaying() && supportsPIP()) {
            PIPVideo();
            return true;
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isVideo) {
            PIPVideo();
        }
    }

    private int getRotation(Uri image) {
        try (final InputStream is = this.getContentResolver().openInputStream(image)) {
            return is == null ? 0 : FileBackend.getRotation(is);
        } catch (final Exception e) {
            return 0;
        }
    }

    private void rotateScreen(final int width, final int height, final int rotation) {
        if (width > height) {
            if (rotation == 0 || rotation == 180) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            }
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private boolean isPlaying() {
        return player != null
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlayWhenReady();
    }

    @Override
    public void onResume() {
        super.onResume();
        startVeloViewCountdown();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (player != null && !isInPictureInPictureMode() && player.getPlaybackState() != Player.STATE_ENDED) {
                if (hasAudioFocus) {
                    player.play();
                }
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (player != null && !isInPictureInPictureMode()) {
                player.pause();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        abandonAudioFocus();
    }

    private boolean hasAudioFocus = false;

    @Override
    public void onStop() {
        if (veloViewOnce != null) {
            // Velo: leaving the viewer in any way ends the single view
            veloViewHandler.removeCallbacks(veloViewTick);
            consumeVeloViewOnce();
            finish();
        }
        if (player != null && (isVideo || isAudio)) {
            player.stop();
        }
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        if (useMaxBrightness()) {
            layout.screenBrightness = -1;
        }
        getWindow().setAttributes(layout);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(oldOrientation);
        super.onStop();
    }

    @Override
    protected void onBackendConnected() {
        if (veloViewOnce != null || veloStory) {
            return; // Velo: only the view-once file or the story, never the chat's other media
        }
        Intent intent = getIntent();
        String convUuid = intent.getStringExtra("conversation_uuid");
        initialMessageUuid = intent.getStringExtra("message_uuid");

        if (convUuid != null) {
            Conversation conversation = xmppConnectionService.findConversationByUuid(convUuid);
            if (conversation != null) {
                xmppConnectionService.getAttachments(conversation, 0, true, this);
                return;
            }
        }

        // Fallback for Media Browser: Try account and jid
        String accountUuid = intent.getStringExtra("account");
        String jidString = intent.getStringExtra("jid");
        if (accountUuid != null && jidString != null) {
            xmppConnectionService.getAttachments(accountUuid, Jid.of(jidString), 0, true, this);
            return;
        }

        if (attachments.isEmpty()) {
            setupSingleMediaFallback(intent);
        }
    }

    private void setupSingleMediaFallback(Intent intent) {
        Uri uri = null;
        String mime = null;
        if (intent.hasExtra("image")) {
            uri = intent.getParcelableExtra("image");
            mime = "image/*";
        } else if (intent.hasExtra("video")) {
            uri = intent.getParcelableExtra("video");
            mime = "video/*";
        } else if (intent.hasExtra("audio")) {
            uri = intent.getParcelableExtra("audio");
            mime = "audio/*";
        }

        if (uri != null) {
            attachments.add(Attachment.of(UUID.randomUUID(), new File(uri.getPath()), mime));
            pagerAdapter.notifyDataSetChanged();
        }
    }

    public boolean useMaxBrightness() {
        return false;
    }

    public boolean useAutoRotateScreen() {
        return false;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasAudioFocus = true;
                if (player != null && (player.getPlayWhenReady() || player.getPlaybackState() == Player.STATE_READY)) {
                    player.play();
                    player.setVolume(1.0f);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasAudioFocus = false;
                if (player != null) {
                    player.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                hasAudioFocus = true;
                if (player != null) {
                    player.setVolume(0.3f);
                }
                break;
        }
    }

    private boolean isDeletableFile(File file) {
        return (file == null || !file.toString().startsWith("/") || file.canWrite());
    }

    private void showFAB() {
        binding.speedDial.show();
    }

    private void hideFAB() {
        binding.speedDial.hide();
    }

    private boolean supportsPIP() {
        if (Compatibility.runsTwentyFour()) {
            return this.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } else {
            return false;
        }
    }

    @Override
    public void onMediaLoaded(List<Attachment> loadedAttachments) {
        runOnUiThread(() -> {
            attachments.clear();
            for (Attachment a : loadedAttachments) {
                String mime = a.getMime();
                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    attachments.add(a);
                }
            }
            pagerAdapter.notifyDataSetChanged();
            final int position = initialPosition();
            if (position < 0) {
                return;
            }
            if (binding.viewPager.getCurrentItem() == position) {
                // The list was replaced under the pager, so the page it is on is not the media
                // it was showing. Nothing will call onPageSelected for a page that does not
                // change, so pick the media up here.
                onMediaItemSelected(attachments.get(position));
            } else {
                binding.viewPager.setCurrentItem(position, false);
            }
        });
    }

    /**
     * Where the pager has to open: on the file that was tapped. The message it belongs to is what
     * identifies it; the file itself is the fallback for the callers that open a file without
     * naming its message. The list is chronological, so a file that is not in it at all (it was
     * just deleted, say) leaves the pager on the newest media instead of the oldest.
     */
    private int initialPosition() {
        if (attachments.isEmpty()) {
            return -1;
        }
        if (initialMessageUuid != null) {
            for (int i = 0; i < attachments.size(); i++) {
                if (attachments.get(i).getUuid().toString().equals(initialMessageUuid)) {
                    return i;
                }
            }
        }
        if (mFile != null) {
            final String path = mFile.getAbsolutePath();
            for (int i = 0; i < attachments.size(); i++) {
                if (path.equals(attachments.get(i).getUri().getPath())) {
                    return i;
                }
            }
        }
        return attachments.size() - 1;
    }

    private class MediaPagerAdapter extends RecyclerView.Adapter<MediaPagerAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMediaViewerBinding b = DataBindingUtil.inflate(LayoutInflater.from(parent.getContext()), R.layout.item_media_viewer, parent, false);
            return new ViewHolder(b);
        }

        @OptIn(markerClass = UnstableApi.class)
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Attachment attachment = attachments.get(position);
            if (attachment.getMime().startsWith("video/") || attachment.getMime().startsWith("audio/")) {
                holder.binding.messageImageView.setVisibility(View.GONE);
                holder.binding.messageVideoView.setVisibility(View.VISIBLE);
                holder.binding.messageVideoView.hideController();
                // Velo 0.28: with several stories a touch moves between them
                holder.binding.messageVideoView.setUseController(!veloStory || attachments.size() < 2);
                // Check if PlayerView is available
                if (position == binding.viewPager.getCurrentItem()) {
                    holder.binding.messageVideoView.setPlayer(player);
                } else {
                    holder.binding.messageVideoView.setPlayer(null);
                }
            } else {
                holder.binding.messageVideoView.setPlayer(null);
                holder.binding.messageVideoView.setVisibility(View.GONE);
                holder.binding.messageImageView.setVisibility(View.VISIBLE);
                Glide.with(MediaViewerActivity.this).load(attachment.getUri()).into(holder.binding.messageImageView);
                holder.binding.messageImageView.setOnPhotoTapListener((view, x, y) -> toggleFAB());
            }
        }

        @Override
        public int getItemCount() { return attachments.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ItemMediaViewerBinding binding;
            ViewHolder(ItemMediaViewerBinding b) { super(b.getRoot()); this.binding = b; }
        }
    }

    private void updateRotation(Attachment attachment) {
        Uri uri = attachment.getUri();
        String mime = attachment.getMime();
        if (mime.startsWith("image/")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(new File(uri.getPath()).getAbsolutePath(), options);
            height = options.outHeight;
            width = options.outWidth;
            rotation = getRotation(uri);
        } else if (mime.startsWith("video/")) {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(uri.getPath());
                height = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                rotation = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            } catch (Exception e) {
                rotation = 0;
            }
        } else if (mime.startsWith("audio/")) {
            height = 0;
            width = 0;
            rotation = 0;
        }
        if (useAutoRotateScreen()) {
            rotateScreen(width, height, rotation);
        }
    }

    private void onMediaItemSelected(Attachment attachment) {
        mFile = new File(attachment.getUri().getPath());
        if (player == null) return;

        final boolean sameMedia = attachment.getUri().equals(currentMediaUri);
        currentMediaUri = attachment.getUri();

        RecyclerView recyclerView = (RecyclerView) binding.viewPager.getChildAt(0);
        if (recyclerView != null) {
            for (int i = 0; i < recyclerView.getChildCount(); i++) {
                View child = recyclerView.getChildAt(i);
                MediaPagerAdapter.ViewHolder h = (MediaPagerAdapter.ViewHolder) recyclerView.getChildViewHolder(child);
                if (h != null) {
                    h.binding.messageVideoView.setPlayer(null);
                }
            }
        }

        if (attachment.getMime().startsWith("video/") || attachment.getMime().startsWith("audio/")) {
            isVideo = attachment.getMime().startsWith("video/");
            isAudio = attachment.getMime().startsWith("audio/");
            isImage = false;
            if (!sameMedia) {
                player.stop();
                player.setMediaItem(MediaItem.fromUri(attachment.getUri()));
                player.prepare();
                player.setPlayWhenReady(true);
                requestAudioFocus();
            }

            int position = binding.viewPager.getCurrentItem();
            MediaPagerAdapter.ViewHolder holder = recyclerView != null
                    ? (MediaPagerAdapter.ViewHolder) recyclerView.findViewHolderForAdapterPosition(position)
                    : null;
            if (holder != null) {
                holder.binding.messageVideoView.setPlayer(player);
            }
        } else {
            isVideo = false;
            isAudio = false;
            isImage = true;
            player.stop();
        }
        updateSpeedDialActions();
        updateRotation(attachment);
    }

    private void updateSpeedDialActions() {
        binding.speedDial.clearActionItems();
        if (veloViewOnce != null || veloStory) {
            // Velo: nothing to share, save or open elsewhere
            binding.speedDial.setVisibility(View.GONE);
            return;
        }
        if (isDeletableFile(mFile)) {
            binding.speedDial.addActionItem(new SpeedDialActionItem.Builder(R.id.action_delete, R.drawable.ic_delete_24dp)
                    .setLabel(R.string.delete)
                    .setFabImageTintColor(ContextCompat.getColor(this, R.color.white))
                    .create());
        }
        binding.speedDial.addActionItem(new SpeedDialActionItem.Builder(R.id.action_share, R.drawable.ic_share_24dp)
                .setLabel(R.string.share)
                .setFabImageTintColor(ContextCompat.getColor(this, R.color.white))
                .create()
        );
        binding.speedDial.addActionItem(new SpeedDialActionItem.Builder(R.id.action_save, R.drawable.ic_save_24dp)
                .setLabel(R.string.save_to_downloads)
                .setFabImageTintColor(ContextCompat.getColor(this, R.color.white))
                .create()
        );

        binding.speedDial.setOnActionSelectedListener(actionItem -> {
            final int actionId = actionItem.getId();
            if (actionId == R.id.action_share) {
                share();
            } else if (actionId == R.id.action_save) {
                new MaterialAlertDialogBuilder(MediaViewerActivity.this)
                        .setTitle(R.string.action_save_to_downloads)
                        .setMessage(R.string.save_to_downloads_warning)
                        .setPositiveButton(R.string.confirm, (dialog, which) -> saveToDownloads(mFile))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            } else if (actionId == R.id.action_delete) {
                deleteFile();
            } else {
                return false;
            }
            return false;
        });
        binding.speedDial.getMainFab().setSupportImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.white)));
    }

    private void toggleFAB() {
        if (veloViewOnce != null || veloStory) return; // Velo: no actions on a view-once file or a story
        if (binding.speedDial.isShown()) hideFAB(); else showFAB();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        currentMediaUri = null;
        initialMessageUuid = intent.getStringExtra("message_uuid");
        consumeVeloViewOnce();
        veloViewOnce = intent.getStringExtra(EXTRA_VELO_VIEW_ONCE);
        veloStory = intent.getBooleanExtra(EXTRA_VELO_STORY, false);
        showInitialMediaImmediately();
        if (xmppConnectionService != null) {
            onBackendConnected();
        }
    }
}