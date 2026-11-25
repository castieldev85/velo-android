package eu.siacs.conversations.velo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;

/**
 * Velo 0.49: transcription of voice messages, entirely on the phone. The audio file is decoded
 * (MediaExtractor + MediaCodec, down-mixed to mono 16-bit PCM) and fed to Vosk with the same
 * model as the dictation ({@link VeloSpeechManager}, language from {@link VeloSpeechLang});
 * nothing leaves the device.
 *
 * <p>The text is kept in the app's encrypted database ({@code velo_transcripts}, never part of a
 * backup) and goes away with its message: {@link #cleanup(DatabaseBackend)} runs with every expiry
 * pass and drops the text of messages that are deleted, retracted, expired or without their file.
 */
public final class VeloTranscriber {

    public interface Callback {
        /** Downloading the model (first time only). */
        void onDownload(long done, long total);
        /** The model is there: decoding and recognising. */
        void onWorking();
        /** Finished; {@code text} is empty when no word was recognised. */
        void onDone(String text);
        void onError(String message);
    }

    private static final String TABLE = "velo_transcripts";
    private static final long TIMEOUT_US = 10_000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** uuid -> text, "" when the database has none (so a bubble asks only once). */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING = java.util.Collections.synchronizedSet(new HashSet<>());
    /** Transcripts the user hid again; they stay hidden while the app runs. */
    private static final Set<String> HIDDEN = java.util.Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean tableReady;

    private VeloTranscriber() {}

    public static boolean isModelReady(final Context context, final String lang) {
        return VeloSpeechManager.isReady(context, lang);
    }

    public static boolean isRunning(final Message message) {
        return RUNNING.contains(message.getUuid());
    }

    public static boolean isHidden(final Message message) {
        return HIDDEN.contains(message.getUuid());
    }

    public static void setHidden(final Message message, final boolean hidden) {
        if (hidden) {
            HIDDEN.add(message.getUuid());
        } else {
            HIDDEN.remove(message.getUuid());
        }
    }

    /** The stored text of this message, or null. Never for a deleted message. */
    @Nullable
    public static String get(final Context context, final Message message) {
        final String uuid = message.getUuid();
        if (message.isDeleted() || message.getRelativeFilePath() == null) {
            if (CACHE.remove(uuid) != null) {
                WORKER.execute(() -> delete(context, uuid));
            }
            return null;
        }
        String text = CACHE.get(uuid);
        if (text == null) {
            text = read(context, uuid);
            CACHE.put(uuid, text == null ? "" : text);
        }
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * Transcribes the message's audio file in {@code lang} (Velo 0.52: one of
     * {@link VeloSpeechLang#LANGS}); a new transcription replaces the old text. Callbacks on the
     * main thread.
     */
    public static void transcribe(
            final Context context,
            final Account account,
            final Message message,
            final File audio,
            final String lang,
            final Callback callback) {
        final String uuid = message.getUuid();
        if (!RUNNING.add(uuid)) {
            return;
        }
        final Context app = context.getApplicationContext();
        WORKER.execute(
                () -> {
                    try {
                        final File dir =
                                VeloSpeechManager.ensureModel(
                                        app,
                                        account,
                                        lang,
                                        new VeloSpeechManager.Progress() {
                                            @Override
                                            public void onDownload(final long done, final long total) {
                                                MAIN.post(() -> callback.onDownload(done, total));
                                            }

                                            @Override
                                            public void onUnpacking() {}

                                            @Override
                                            public boolean wanted() {
                                                return true;
                                            }
                                        });
                        MAIN.post(callback::onWorking);
                        Model model;
                        try {
                            model = new Model(dir.getAbsolutePath());
                        } catch (final IOException unreadable) {
                            // a broken model is fetched again once, as the dictation does
                            VeloSpeechManager.discard(app, lang);
                            model = new Model(VeloSpeechManager.ensureModel(app, account, lang,
                                    new VeloSpeechManager.Progress() {
                                        @Override
                                        public void onDownload(final long done, final long total) {}

                                        @Override
                                        public void onUnpacking() {}

                                        @Override
                                        public boolean wanted() {
                                            return true;
                                        }
                                    }).getAbsolutePath());
                        }
                        final String text;
                        try {
                            text = recognise(model, audio);
                        } finally {
                            model.close();
                        }
                        if (!text.isEmpty()) {
                            write(app, uuid, text);
                        }
                        CACHE.put(uuid, text);
                        HIDDEN.remove(uuid);
                        RUNNING.remove(uuid);
                        MAIN.post(() -> callback.onDone(text));
                    } catch (final Throwable t) {
                        RUNNING.remove(uuid);
                        eu.siacs.conversations.velo.Log.w(Config.LOGTAG, "velo transcription failed", t);
                        final String why = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                        MAIN.post(() -> callback.onError(why));
                    }
                });
    }

    /** Decodes {@code audio} and runs Vosk over it; returns the text, maybe empty. */
    static String recognise(final Model model, final File audio) throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        Recognizer recognizer = null;
        final StringBuilder out = new StringBuilder();
        try {
            extractor.setDataSource(audio.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("no audio track");
            }
            extractor.selectTrack(track);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            boolean floatPcm = false;
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    final int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(in);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat outFormat = codec.getOutputFormat();
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= 24 && outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                    if (recognizer != null) {
                        appendResult(out, recognizer.getFinalResult());
                        recognizer.close();
                    }
                    recognizer = new Recognizer(model, sampleRate);
                } else if (outIndex >= 0) {
                    final ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        if (recognizer == null) {
                            recognizer = new Recognizer(model, sampleRate);
                        }
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        final byte[] mono = toMono16(buffer.slice().order(ByteOrder.nativeOrder()),
                                Math.max(1, channels), floatPcm);
                        if (recognizer.acceptWaveForm(mono, mono.length)) {
                            appendResult(out, recognizer.getResult());
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            if (recognizer != null) {
                appendResult(out, recognizer.getFinalResult());
            }
        } finally {
            if (recognizer != null) {
                recognizer.close();
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (final Exception ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
        return out.toString().trim();
    }

    /** Interleaved PCM (16-bit or float) to mono 16-bit little-endian. */
    private static byte[] toMono16(final ByteBuffer pcm, final int channels, final boolean floatPcm) {
        final int bytesPerSample = floatPcm ? 4 : 2;
        final int frames = pcm.remaining() / (bytesPerSample * channels);
        final byte[] out = new byte[frames * 2];
        for (int f = 0; f < frames; f++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += floatPcm ? pcm.getFloat() * 32767f : pcm.getShort();
            }
            int v = Math.round(sum / channels);
            v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            out[2 * f] = (byte) (v & 0xff);
            out[2 * f + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }

    private static void appendResult(final StringBuilder out, final String json) {
        if (json == null) {
            return;
        }
        try {
            final String text = new JSONObject(json).optString("text", "").trim();
            if (!text.isEmpty()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(text);
            }
        } catch (final org.json.JSONException ignored) {
        }
    }

    // --- storage: the app's encrypted database ---

    private static SQLiteDatabase db(final Context context) {
        final SQLiteDatabase db = DatabaseBackend.getInstance(context).getWritableDatabase();
        if (!tableReady) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE
                    + " (uuid TEXT PRIMARY KEY, text TEXT NOT NULL)");
            tableReady = true;
        }
        return db;
    }

    @Nullable
    private static String read(final Context context, final String uuid) {
        try (final Cursor c = db(context).rawQuery(
                "SELECT text FROM " + TABLE + " WHERE uuid=?", new String[] {uuid})) {
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (final Exception e) {
            eu.siacs.conversations.velo.Log.w(Config.LOGTAG, "velo transcript read failed", e);
            return null;
        }
    }

    private static void write(final Context context, final String uuid, final String text) {
        final ContentValues values = new ContentValues();
        values.put("uuid", uuid);
        values.put("text", text);
        db(context).insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static void delete(final Context context, final String uuid) {
        try {
            db(context).delete(TABLE, "uuid=?", new String[] {uuid});
        } catch (final Exception ignored) {
        }
    }

    /**
     * Drops the text of every message that is gone (deleted, retracted, expired, file removed).
     * Called with each expiry pass of the service.
     */
    public static void cleanup(final DatabaseBackend backend) {
        try {
            final SQLiteDatabase db = backend.getWritableDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE
                    + " (uuid TEXT PRIMARY KEY, text TEXT NOT NULL)");
            tableReady = true;
            db.execSQL("DELETE FROM " + TABLE + " WHERE uuid NOT IN (SELECT "
                    + Message.UUID + " FROM " + Message.TABLENAME + " WHERE "
                    + Message.DELETED + "=0 AND " + Message.RELATIVE_FILE_PATH + " IS NOT NULL)");
            CACHE.clear();
        } catch (final Exception e) {
            eu.siacs.conversations.velo.Log.w(Config.LOGTAG, "velo transcript cleanup failed", e);
        }
    }
}
