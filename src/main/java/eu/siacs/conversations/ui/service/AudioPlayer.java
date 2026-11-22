package eu.siacs.conversations.ui.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import eu.siacs.conversations.velo.Log;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.common.primitives.Ints;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.adapter.MessageAdapter;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.WeakReferenceSet;

/**
 * Per-{@link MessageAdapter} binder for audio-message bubbles. The actual playback is owned by the
 * app-scoped {@link BackgroundAudioController} (backed by a media3 background service), so leaving
 * the chat or backgrounding the app no longer stops audio. This class only reflects controller
 * state into the visible bubbles and forwards user actions.
 */
public class AudioPlayer
        implements View.OnClickListener,
                SeekBar.OnSeekBarChangeListener,
                BackgroundAudioController.Listener {

    private final MessageAdapter messageAdapter;
    private final BackgroundAudioController controller;
    private final WeakReferenceSet<RelativeLayout> audioPlayerLayouts = new WeakReferenceSet<>();
    private final PendingItem<WeakReference<MaterialButton>> pendingOnClickView =
            new PendingItem<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();

    public AudioPlayer(final MessageAdapter adapter) {
        this.messageAdapter = adapter;
        this.controller = BackgroundAudioController.getInstance(adapter.getContext());
    }

    private static String formatTime(final int ms) {
        return TimeFrameUtils.formatElapsedTime(ms, false);
    }

    public void init(final RelativeLayout audioPlayer, final Message message) {
        audioPlayer.setTag(message);
        // Velo 0.49: the buttons sit in an inner block now and read the message from their parent
        final View playerRow = audioPlayer.findViewById(R.id.velo_player_row);
        if (playerRow != null) {
            playerRow.setTag(message);
        }
        this.audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
        this.controller.addListener(this);
        init(ViewHolder.get(audioPlayer), message);
    }

    private void init(final ViewHolder viewHolder, final Message message) {
        MessageAdapter.setTextColor(viewHolder.runtime, viewHolder.bubbleColor);
        MessageAdapter.setTextColor(viewHolder.title, viewHolder.bubbleColor);
        // Velo: a recycled bubble must not keep the title of another file
        viewHolder.title.setText(null);
        viewHolder.progress.setOnSeekBarChangeListener(this);
        executor.execute(
                () -> {
                    final MediaMetadataRetriever mediaMetadataRetriever =
                            new MediaMetadataRetriever();
                    try {
                        mediaMetadataRetriever.setDataSource(message.getRelativeFilePath());
                        final String artist =
                                mediaMetadataRetriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_ARTIST);
                        final String album =
                                mediaMetadataRetriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_TITLE);
                        if (artist != null && album != null) {
                            handler.post(
                                    () -> {
                                        viewHolder.title.setText(
                                                String.format("%s - %s", artist, album));
                                        // Velo: a song, not a voice message: no "Transcribe"
                                        veloApplyTranscript(viewHolder, message);
                                    });
                        }
                    } catch (final Exception e) {
                        Log.w(Config.LOGTAG, e);
                    } finally {
                        try {
                            mediaMetadataRetriever.release();
                        } catch (final Exception e) {
                            Log.e(Config.LOGTAG, "Error releasing MediaMetadataRetriever", e);
                        }
                    }
                });
        final ColorStateList color =
                MessageAdapter.bubbleToOnSurfaceColorStateList(
                        viewHolder.progress, viewHolder.bubbleColor);
        viewHolder.progress.setThumbTintList(color);
        viewHolder.progress.setProgressTintList(color);
        viewHolder.playPause.setOnClickListener(this);
        viewHolder.playbackSpeed.setTextColor(color);
        viewHolder.playbackSpeed.setStrokeColor(color);
        viewHolder.playbackSpeed.setOnClickListener(this);
        viewHolder.transcribe.setTextColor(color);
        viewHolder.transcribe.setStrokeColor(color);
        viewHolder.transcribe.setIconTint(color);
        viewHolder.transcribe.setIconResource(R.drawable.subject);
        viewHolder.transcribe.setOnClickListener(this);
        MessageAdapter.setTextColor(viewHolder.transcript, viewHolder.bubbleColor);
        applyState(viewHolder, message);
        veloApplyTranscript(viewHolder, message);
    }

    // region Velo 0.49: transcription of voice messages (VeloTranscriber)

    /** uuid -> what the button says while a transcription runs ("Transcribing…", "12%"). */
    private static final java.util.Map<String, String> VELO_STATUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void veloApplyTranscript(final ViewHolder viewHolder, final Message message) {
        final Context context = viewHolder.transcribe.getContext();
        final boolean song = viewHolder.title.getText() != null
                && viewHolder.title.getText().length() > 0;
        if (song || message.isDeleted() || message.getRelativeFilePath() == null) {
            viewHolder.transcribe.setVisibility(View.GONE);
            viewHolder.transcript.setVisibility(View.GONE);
            return;
        }
        viewHolder.transcribe.setVisibility(View.VISIBLE);
        final String status = VELO_STATUS.get(message.getUuid());
        if (status != null && eu.siacs.conversations.velo.VeloTranscriber.isRunning(message)) {
            viewHolder.transcribe.setText(status);
            viewHolder.transcribe.setEnabled(false);
            viewHolder.transcript.setVisibility(View.GONE);
            return;
        }
        viewHolder.transcribe.setEnabled(true);
        final String text = eu.siacs.conversations.velo.VeloTranscriber.get(context, message);
        if (text == null) {
            viewHolder.transcribe.setText(R.string.velo_transcribe);
            viewHolder.transcript.setVisibility(View.GONE);
            return;
        }
        final boolean hidden = eu.siacs.conversations.velo.VeloTranscriber.isHidden(message);
        viewHolder.transcribe.setText(hidden ? R.string.velo_transcribe_show : R.string.velo_transcribe_hide);
        if (hidden) {
            viewHolder.transcript.setVisibility(View.GONE);
            return;
        }
        final android.text.SpannableStringBuilder shown = new android.text.SpannableStringBuilder(text);
        shown.append('\n');
        final int start = shown.length();
        shown.append(context.getString(R.string.velo_transcript_note));
        shown.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                start, shown.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        shown.setSpan(new android.text.style.RelativeSizeSpan(0.8f),
                start, shown.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        viewHolder.transcript.setText(shown);
        viewHolder.transcript.setVisibility(View.VISIBLE);
    }

    private void veloRefreshTranscripts() {
        for (final WeakReference<RelativeLayout> reference : audioPlayerLayouts) {
            final RelativeLayout audioPlayer = reference.get();
            if (audioPlayer == null) {
                continue;
            }
            final Message message = (Message) audioPlayer.getTag();
            if (message != null) {
                veloApplyTranscript(ViewHolder.get(audioPlayer), message);
            }
        }
    }

    private void veloTranscribeClicked(final View button) {
        final RelativeLayout audioPlayer = (RelativeLayout) button.getParent();
        final Message message = (Message) audioPlayer.getTag();
        if (message == null || eu.siacs.conversations.velo.VeloTranscriber.isRunning(message)) {
            return;
        }
        final Context context = messageAdapter.getActivity();
        if (eu.siacs.conversations.velo.VeloTranscriber.get(context, message) != null) {
            eu.siacs.conversations.velo.VeloTranscriber.setHidden(
                    message, !eu.siacs.conversations.velo.VeloTranscriber.isHidden(message));
            veloRefreshTranscripts();
            return;
        }
        if (eu.siacs.conversations.velo.VeloTranscriber.isModelReady(context)) {
            veloStartTranscription(message);
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.velo_transcribe_model_title)
                .setMessage(R.string.velo_transcribe_model_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.velo_transcribe_download,
                        (dialog, which) -> veloStartTranscription(message))
                .show();
    }

    private void veloStartTranscription(final Message message) {
        final Context context = messageAdapter.getActivity();
        final String uuid = message.getUuid();
        final java.io.File audio = messageAdapter.getFileBackend().getFile(message);
        VELO_STATUS.put(uuid, context.getString(R.string.velo_transcribing));
        // the model comes from the server of an account that is online: the message's own first
        eu.siacs.conversations.entities.Account account = message.getConversation().getAccount();
        final var service = messageAdapter.getActivity() instanceof eu.siacs.conversations.ui.XmppActivity x
                ? x.xmppConnectionService : null;
        if (!account.isOnlineAndConnected() && service != null) {
            for (final eu.siacs.conversations.entities.Account a : service.getAccounts()) {
                if (a.isOnlineAndConnected()) {
                    account = a;
                    break;
                }
            }
        }
        eu.siacs.conversations.velo.VeloTranscriber.transcribe(
                context,
                account,
                message,
                audio,
                new eu.siacs.conversations.velo.VeloTranscriber.Callback() {
                    @Override
                    public void onDownload(final long done, final long total) {
                        VELO_STATUS.put(uuid, context.getString(R.string.velo_transcribe_downloading,
                                total > 0 ? (int) (done * 100 / total) : 0));
                        veloRefreshTranscripts();
                    }

                    @Override
                    public void onWorking() {
                        VELO_STATUS.put(uuid, context.getString(R.string.velo_transcribing));
                        veloRefreshTranscripts();
                    }

                    @Override
                    public void onDone(final String text) {
                        VELO_STATUS.remove(uuid);
                        if (text.isEmpty()) {
                            android.widget.Toast.makeText(context, R.string.velo_transcribe_empty,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                        veloRefreshTranscripts();
                    }

                    @Override
                    public void onError(final String why) {
                        VELO_STATUS.remove(uuid);
                        android.widget.Toast.makeText(context,
                                context.getString(R.string.velo_transcribe_failed, why),
                                android.widget.Toast.LENGTH_LONG).show();
                        veloRefreshTranscripts();
                    }
                });
        veloRefreshTranscripts();
    }

    // endregion

    /** Renders the play/pause icon, seekbar progress and runtime for a single bubble. */
    private void applyState(final ViewHolder viewHolder, final Message message) {
        final Context context = viewHolder.playPause.getContext();
        final String uuid = message.getUuid();
        if (controller.isCurrent(uuid)) {
            final boolean playing = controller.isPlaying();
            viewHolder.playPause.setIconResource(
                    playing ? R.drawable.rounded_pause_36 : R.drawable.rounded_play_arrow_36);
            viewHolder.playPause.setContentDescription(
                    context.getString(playing ? R.string.pause_audio : R.string.play_audio));
            viewHolder.progress.setEnabled(playing);
            final int duration = controller.getDurationMs();
            final int current = controller.getCurrentPositionMs();
            if (duration > 0) {
                viewHolder.progress.setProgress(
                        Math.min(Ints.saturatedCast(current * 100L / duration), 100));
                viewHolder.runtime.setText(
                        String.format("%s / %s", formatTime(current), formatTime(duration)));
            }
            applySpeed(viewHolder.playbackSpeed, controller.getPlaybackSpeed());
            viewHolder.playbackSpeed.setVisibility(View.VISIBLE);
        } else {
            // INVISIBLE, not GONE: keep the space so the bubble doesn't resize on play/stop
            viewHolder.playbackSpeed.setVisibility(View.INVISIBLE);
            viewHolder.playPause.setIconResource(R.drawable.rounded_play_arrow_36);
            viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
            viewHolder.runtime.setText(formatTime(message.getFileParams().runtime));
            viewHolder.progress.setProgress(0);
            viewHolder.progress.setEnabled(false);
        }
    }

    /** Shared by the bubble and the mini-player so both pills look and read the same. */
    static void applySpeed(final MaterialButton button, final float speed) {
        final String label = BackgroundAudioController.formatSpeed(speed);
        button.setText(label);
        button.setContentDescription(
                String.format(
                        "%s, %s", button.getContext().getString(R.string.playback_speed), label));
    }

    @Override
    public void onClick(final View v) {
        if (v.getId() == R.id.play_pause) {
            startStop((MaterialButton) v);
        } else if (v.getId() == R.id.playback_speed) {
            controller.cyclePlaybackSpeed();
        } else if (v.getId() == R.id.velo_transcribe) {
            veloTranscribeClicked(v);
        }
    }

    private void startStop(final MaterialButton playPause) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                messageAdapter.getActivity(),
                                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingOnClickView.push(new WeakReference<>(playPause));
            ActivityCompat.requestPermissions(
                    messageAdapter.getActivity(),
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    ConversationsActivity.REQUEST_PLAY_PAUSE);
            return;
        }
        final RelativeLayout audioPlayer = (RelativeLayout) playPause.getParent();
        final Message message = (Message) audioPlayer.getTag();
        if (controller.isCurrent(message.getUuid())) {
            controller.toggleCurrentPlayPause();
        } else {
            final Uri uri =
                    Uri.fromFile(messageAdapter.getFileBackend().getFile(message));
            final long duration =
                    message.getFileParams() != null ? message.getFileParams().runtime : 0;
            controller.play(message, uri, duration, conversationTitle(message));
        }
    }

    private String conversationTitle(final Message message) {
        final Conversational conversation = message.getConversation();
        if (conversation instanceof Conversation) {
            return ((Conversation) conversation).getName().toString();
        }
        return null;
    }

    public void startStopPending() {
        final var reference = pendingOnClickView.pop();
        if (reference != null) {
            final var imageButton = reference.get();
            if (imageButton != null) {
                startStop(imageButton);
            }
        }
    }

    @Override
    public void onProgressChanged(
            final SeekBar seekBar, final int progress, final boolean fromUser) {
        final RelativeLayout audioPlayer = (RelativeLayout) seekBar.getParent();
        final Message message = (Message) audioPlayer.getTag();
        if (fromUser && message != null && controller.isCurrent(message.getUuid())) {
            controller.seekToFraction(progress / 100f);
        }
    }

    @Override
    public void onStartTrackingTouch(final SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(final SeekBar seekBar) {}

    // region BackgroundAudioController.Listener
    @Override
    public void onAudioStateChanged() {
        for (final WeakReference<RelativeLayout> reference : audioPlayerLayouts) {
            final RelativeLayout audioPlayer = reference.get();
            if (audioPlayer == null || audioPlayer.getVisibility() != View.VISIBLE) {
                continue;
            }
            final Message message = (Message) audioPlayer.getTag();
            if (message != null) {
                applyState(ViewHolder.get(audioPlayer), message);
            }
        }
    }

    @Override
    public void onAudioProgress(final int currentMs, final int durationMs, final float fraction) {
        final Message current = controller.getCurrentMessage();
        if (current == null) {
            return;
        }
        for (final WeakReference<RelativeLayout> reference : audioPlayerLayouts) {
            final RelativeLayout audioPlayer = reference.get();
            if (audioPlayer == null || audioPlayer.getVisibility() != View.VISIBLE) {
                continue;
            }
            final Message message = (Message) audioPlayer.getTag();
            if (message == null || !message.getUuid().equals(current.getUuid())) {
                continue;
            }
            final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
            viewHolder.progress.setProgress(
                    durationMs > 0
                            ? Math.min(Ints.saturatedCast(currentMs * 100L / durationMs), 100)
                            : 100);
            viewHolder.runtime.setText(
                    String.format("%s / %s", formatTime(currentMs), formatTime(durationMs)));
        }
    }
    // endregion

    /** Detach this binder's UI from the controller without stopping background playback. */
    public void stop() {
        this.controller.removeListener(this);
        this.audioPlayerLayouts.clear();
    }

    /** Detach the controller listener (called when the chat is no longer visible). */
    public void unregisterListener() {
        this.controller.removeListener(this);
    }

    public static class ViewHolder {
        private TextView runtime;
        private TextView title;
        private SeekBar progress;
        private MaterialButton playPause;
        private MaterialButton playbackSpeed;
        private MaterialButton transcribe;
        private TextView transcript;
        private MessageAdapter.BubbleColor bubbleColor = MessageAdapter.BubbleColor.SURFACE;

        public static ViewHolder get(final RelativeLayout audioPlayer) {
            final var existingViewHolder =
                    (ViewHolder) audioPlayer.getTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER);
            if (existingViewHolder != null) {
                return existingViewHolder;
            }
            final ViewHolder viewHolder = new ViewHolder();
            viewHolder.runtime = audioPlayer.findViewById(R.id.runtime);
            viewHolder.title = audioPlayer.findViewById(R.id.title);
            viewHolder.progress = audioPlayer.findViewById(R.id.progress);
            viewHolder.playPause = audioPlayer.findViewById(R.id.play_pause);
            viewHolder.playbackSpeed = audioPlayer.findViewById(R.id.playback_speed);
            viewHolder.transcribe = audioPlayer.findViewById(R.id.velo_transcribe);
            viewHolder.transcript = audioPlayer.findViewById(R.id.velo_transcript);
            audioPlayer.setTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER, viewHolder);
            return viewHolder;
        }

        public void setBubbleColor(final MessageAdapter.BubbleColor bubbleColor) {
            this.bubbleColor = bubbleColor;
        }
    }
}
