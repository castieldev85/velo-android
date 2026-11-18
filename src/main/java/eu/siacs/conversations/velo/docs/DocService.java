package eu.siacs.conversations.velo.docs;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Velo: reads documents in an isolated process (see the manifest), with no network, no access to the
 * app's files and a different user id. It only gets the descriptor of the file to read and a pipe to
 * write to, so a file built to attack the parser finds neither the keys nor the chats, and if it
 * crashes or hangs only this process goes.
 */
public class DocService extends Service {

    static final int CONVERT = 1;
    static final int PDF_INFO = 2;
    static final int PDF_PAGE = 3;

    static final String KEY_IN = "in";
    static final String KEY_OUT = "out";
    static final String KEY_NAME = "name";
    static final String KEY_MIME = "mime";
    static final String KEY_PAGE = "page";
    static final String KEY_SIZE = "size";
    static final String KEY_MODE = "mode";

    /** The page fits inside a square of this size. */
    static final int MODE_FIT = 0;

    /** The page covers a square of this size (it gets cropped to a thumbnail afterwards). */
    static final int MODE_COVER = 1;

    /** The page is this wide. */
    static final int MODE_WIDTH = 2;

    static final int MAX_SIDE = 4096;
    private static final long WATCHDOG_MS = 120_000;

    private HandlerThread thread;
    private Messenger messenger;
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private final Runnable suicide = () -> Process.killProcess(Process.myPid());

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("velo-doc");
        thread.start();
        messenger =
                new Messenger(
                        new Handler(thread.getLooper()) {
                            @Override
                            public void handleMessage(final Message message) {
                                watchdog.postDelayed(suicide, WATCHDOG_MS);
                                try {
                                    DocService.this.handle(message.what, message.getData());
                                } finally {
                                    watchdog.removeCallbacks(suicide);
                                }
                            }
                        });
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        thread.quit();
        super.onDestroy();
    }

    private void handle(final int what, final Bundle data) {
        final ParcelFileDescriptor input = data.getParcelable(KEY_IN);
        final ParcelFileDescriptor output = data.getParcelable(KEY_OUT);
        if (input == null || output == null) {
            return;
        }
        try (final DataOutputStream out =
                new DataOutputStream(
                        new BufferedOutputStream(
                                new ParcelFileDescriptor.AutoCloseOutputStream(output),
                                64 * 1024))) {
            switch (what) {
                case CONVERT -> convert(input, data, out);
                case PDF_INFO -> pdfInfo(input, out);
                case PDF_PAGE -> pdfPage(input, data, out);
                default -> {}
            }
        } catch (final IOException e) {
            // the other side went away or the pipe broke: it sees the end of the stream
        } finally {
            closeQuietly(input);
        }
    }

    private void convert(
            final ParcelFileDescriptor input, final Bundle data, final DataOutputStream out)
            throws IOException {
        final String html;
        try (final InputStream stream =
                new java.io.BufferedInputStream(
                        new java.io.FileInputStream(input.getFileDescriptor()))) {
            html =
                    DocConverter.convert(
                            this, stream, data.getString(KEY_NAME), data.getString(KEY_MIME));
        } catch (final DocConverter.Failure e) {
            out.writeInt(e.status);
            return;
        }
        final byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        out.writeInt(DocConverter.OK);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private void pdfInfo(final ParcelFileDescriptor input, final DataOutputStream out)
            throws IOException {
        try (final PdfRenderer renderer = new PdfRenderer(input)) {
            final int count = renderer.getPageCount();
            if (count < 1) {
                out.writeInt(DocConverter.UNREADABLE);
                return;
            }
            try (final PdfRenderer.Page page = renderer.openPage(0)) {
                out.writeInt(DocConverter.OK);
                out.writeInt(count);
                out.writeInt(page.getWidth());
                out.writeInt(page.getHeight());
            }
        } catch (final SecurityException e) {
            out.writeInt(DocConverter.PROTECTED);
        } catch (final RuntimeException | IOException e) {
            out.writeInt(DocConverter.UNREADABLE);
        }
    }

    private void pdfPage(
            final ParcelFileDescriptor input, final Bundle data, final DataOutputStream out)
            throws IOException {
        final Bitmap bitmap;
        try (final PdfRenderer renderer = new PdfRenderer(input);
                final PdfRenderer.Page page = renderer.openPage(data.getInt(KEY_PAGE))) {
            final int[] size =
                    scale(
                            page.getWidth(),
                            page.getHeight(),
                            data.getInt(KEY_SIZE),
                            data.getInt(KEY_MODE));
            bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xffffffff);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        } catch (final SecurityException e) {
            out.writeInt(DocConverter.PROTECTED);
            return;
        } catch (final RuntimeException | OutOfMemoryError | IOException e) {
            out.writeInt(DocConverter.UNREADABLE);
            return;
        }
        final ByteBuffer pixels = ByteBuffer.allocate(bitmap.getAllocationByteCount());
        bitmap.copyPixelsToBuffer(pixels);
        out.writeInt(DocConverter.OK);
        out.writeInt(bitmap.getWidth());
        out.writeInt(bitmap.getHeight());
        out.write(pixels.array(), 0, pixels.position());
        bitmap.recycle();
    }

    private static int[] scale(final int width, final int height, final int size, final int mode) {
        final double ratio = (double) width / Math.max(height, 1);
        final int target = Math.max(1, Math.min(size, MAX_SIDE));
        int w, h;
        if (mode == MODE_WIDTH
                || (mode == MODE_FIT) == (width >= height)) {
            w = target;
            h = (int) Math.round(target / ratio);
        } else {
            h = target;
            w = (int) Math.round(target * ratio);
        }
        // very long or very wide pages: stay inside the limit, keep the proportions
        if (w > MAX_SIDE || h > MAX_SIDE) {
            final double shrink = (double) MAX_SIDE / Math.max(w, h);
            w = (int) (w * shrink);
            h = (int) (h * shrink);
        }
        return new int[] {Math.max(w, 1), Math.max(h, 1)};
    }

    private static void closeQuietly(final ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (final IOException ignored) {
            // nothing else to do with it
        }
    }
}
