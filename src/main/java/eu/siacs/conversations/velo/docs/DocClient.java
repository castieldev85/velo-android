package eu.siacs.conversations.velo.docs;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Velo: talks to {@link DocService}. Blocking, so never on the main thread. What comes back is
 * treated as untrusted too: sizes are checked before anything is allocated.
 */
public final class DocClient implements Closeable {

    private static final int BIND_TIMEOUT_S = 15;
    private static final int MAX_HTML_BYTES = 128 * 1024 * 1024;

    public static final class Failed extends Exception {
        public final int status;

        Failed(final int status) {
            this.status = status;
        }
    }

    public record PdfInfo(int pages, int width, int height) {}

    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile Messenger messenger;
    private final ServiceConnection connection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(final ComponentName name, final IBinder service) {
                    messenger = new Messenger(service);
                    connected.countDown();
                }

                @Override
                public void onServiceDisconnected(final ComponentName name) {
                    messenger = null;
                }
            };

    private DocClient(final Context context) {
        this.context = context.getApplicationContext();
    }

    public static DocClient connect(final Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // the connection callback arrives on this very thread: waiting here never ends
            throw new IOException("DocClient on the main thread");
        }
        final DocClient client = new DocClient(context);
        final Intent intent = new Intent(client.context, DocService.class);
        if (!client.context.bindService(intent, client.connection, Context.BIND_AUTO_CREATE)) {
            client.close();
            throw new IOException("could not bind DocService");
        }
        try {
            if (!client.connected.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) {
                client.close();
                throw new IOException("DocService did not start");
            }
        } catch (final InterruptedException e) {
            client.close();
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return client;
    }

    @Override
    public void close() {
        try {
            context.unbindService(connection);
        } catch (final IllegalArgumentException ignored) {
            // it was never bound
        }
    }

    private DataInputStream request(
            final int what, final ParcelFileDescriptor input, final Bundle extras)
            throws IOException {
        final Messenger target = messenger;
        if (target == null) {
            throw new IOException("DocService is gone");
        }
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        final Bundle data = extras == null ? new Bundle() : extras;
        data.putParcelable(DocService.KEY_IN, input);
        data.putParcelable(DocService.KEY_OUT, pipe[1]);
        final Message message = Message.obtain(null, what);
        message.setData(data);
        try {
            target.send(message);
        } catch (final RemoteException e) {
            pipe[0].close();
            throw new IOException(e);
        } finally {
            // the service has its own copy: holding ours would hide the end of the stream
            pipe[1].close();
        }
        return new DataInputStream(
                new BufferedInputStream(
                        new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), 64 * 1024));
    }

    private static void check(final DataInputStream in) throws IOException, Failed {
        final int status = in.readInt();
        if (status != DocConverter.OK) {
            throw new Failed(status);
        }
    }

    /** The document as HTML; {@link Failed} with {@code IS_PDF} means it should go through the PDF calls. */
    public String convert(final ParcelFileDescriptor input, final String name, final String mime)
            throws IOException, Failed {
        final Bundle extras = new Bundle();
        extras.putString(DocService.KEY_NAME, name);
        extras.putString(DocService.KEY_MIME, mime);
        try (final DataInputStream in = request(DocService.CONVERT, input, extras)) {
            check(in);
            final int length = in.readInt();
            if (length < 0 || length > MAX_HTML_BYTES) {
                throw new IOException("bad length");
            }
            final byte[] bytes = new byte[length];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public PdfInfo pdfInfo(final ParcelFileDescriptor input) throws IOException, Failed {
        try (final DataInputStream in = request(DocService.PDF_INFO, input, null)) {
            check(in);
            final int pages = in.readInt();
            final int width = in.readInt();
            final int height = in.readInt();
            if (pages < 1 || width < 0 || height < 0) {
                throw new IOException("bad pdf info");
            }
            return new PdfInfo(pages, width, height);
        }
    }

    public Bitmap pdfPage(
            final ParcelFileDescriptor input, final int page, final int size, final int mode)
            throws IOException, Failed {
        final Bundle extras = new Bundle();
        extras.putInt(DocService.KEY_PAGE, page);
        extras.putInt(DocService.KEY_SIZE, size);
        extras.putInt(DocService.KEY_MODE, mode);
        try (final DataInputStream in = request(DocService.PDF_PAGE, input, extras)) {
            check(in);
            final int width = in.readInt();
            final int height = in.readInt();
            if (width < 1 || height < 1
                    || width > DocService.MAX_SIDE || height > DocService.MAX_SIDE) {
                throw new IOException("bad page size");
            }
            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final byte[] pixels = new byte[bitmap.getAllocationByteCount()];
            try {
                in.readFully(pixels);
            } catch (final EOFException e) {
                bitmap.recycle();
                throw e;
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            return bitmap;
        }
    }

    /** The first page of a PDF for a thumbnail in the chat, in its own short connection. */
    public static Bitmap pdfThumbnail(
            final Context context, final ParcelFileDescriptor input, final int size, final boolean fit)
            throws IOException {
        try (final DocClient client = connect(context)) {
            return client.pdfPage(
                    input, 0, size, fit ? DocService.MODE_FIT : DocService.MODE_COVER);
        } catch (final Failed e) {
            throw new IOException("pdf status " + e.status);
        } finally {
            input.close();
        }
    }

    /** The page size of a PDF, for the space its thumbnail takes in the chat. */
    public static PdfInfo pdfInfo(final Context context, final ParcelFileDescriptor input)
            throws IOException {
        try (final DocClient client = connect(context)) {
            return client.pdfInfo(input);
        } catch (final Failed e) {
            throw new IOException("pdf status " + e.status);
        } finally {
            input.close();
        }
    }
}
