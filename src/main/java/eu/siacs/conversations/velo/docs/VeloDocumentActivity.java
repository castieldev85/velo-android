package eu.siacs.conversations.velo.docs;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.ViewUtil;

/**
 * Velo: shows documents inside the app, so they never reach another app. The reading itself happens
 * in {@link DocService}; here the page has no JavaScript, no network and no file access, so a
 * document can't run anything or send traffic outside Tor.
 */
public class VeloDocumentActivity extends XmppActivity {

    private static final String EXTRA_PATH = "velo_path";
    private static final String EXTRA_NAME = "velo_name";
    private static final String EXTRA_MIME = "velo_mime";
    private static final String PREVIEW_FOLDER = "velo-preview";

    private static final String PAGE_HEAD =
            "<!doctype html><html><head><meta charset=\"utf-8\">"
                    + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none';"
                    + " img-src data:; style-src 'unsafe-inline'\">"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                    + "<style>"
                    + "html,body{background:#fff;color:#1a1a1a;margin:0}"
                    + "body{padding:16px;font:16px/1.45 sans-serif;overflow-wrap:break-word}"
                    + ".p{min-height:1.2em;margin:0 0 .5em}"
                    + ".h1{font-size:1.6em;font-weight:bold;margin:.8em 0 .4em}"
                    + ".h2{font-size:1.35em;font-weight:bold;margin:.8em 0 .4em}"
                    + ".h3,.h4,.h5,.h6{font-size:1.1em;font-weight:bold;margin:.7em 0 .3em}"
                    + "img{max-width:100%;height:auto;display:block;margin:.5em 0}"
                    + ".scroll{overflow-x:auto;margin:.5em 0}"
                    + "table{border-collapse:collapse}"
                    + "td{border:1px solid #bbb;padding:4px 8px;vertical-align:top;min-width:1em}"
                    + "td .p{margin:0}"
                    + "td.n{text-align:right;white-space:nowrap}"
                    + "pre{white-space:pre-wrap;font:14px/1.4 monospace;margin:0}"
                    + ".slide{border:1px solid #ccc;border-radius:8px;padding:12px;margin:0 0 16px}"
                    + ".slidenum{color:#666;font-size:.8em;margin-bottom:6px}"
                    + ".note{color:#666;font-style:italic}"
                    + "</style></head><body>";
    private static final String PAGE_TAIL = "</body></html>";

    /** Opens a downloaded file that isn't an image, a video or an audio. */
    public static void open(
            final Context context, final File file, final String name, final String mime) {
        final Intent intent = new Intent(context, VeloDocumentActivity.class);
        intent.putExtra(EXTRA_PATH, file.getAbsolutePath());
        start(context, intent, name, mime);
    }

    /** Opens a file picked for sending: it may not be a plain file of ours yet. */
    public static void preview(final Context context, final Attachment attachment) {
        final Uri uri = attachment.getUri();
        final String mime = attachment.getMime() == null ? "*/*" : attachment.getMime();
        final boolean media =
                mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/");
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            final File file = new File(uri.getPath());
            ViewUtil.view(context, file, mime, file.getName(), null, null);
        } else if (media) {
            // the media viewer only reads plain files
            final Context app = context.getApplicationContext();
            new Thread(
                            () -> {
                                final File copy = copyForPreview(app, uri);
                                if (context instanceof Activity activity) {
                                    activity.runOnUiThread(
                                            () -> {
                                                if (copy != null) {
                                                    ViewUtil.view(activity, copy, mime, copy.getName(), null, null);
                                                } else {
                                                    Toast.makeText(activity, R.string.velo_doc_unreadable, Toast.LENGTH_SHORT).show();
                                                }
                                            });
                                }
                            },
                            "velo-preview")
                    .start();
        } else {
            final Intent intent = new Intent(context, VeloDocumentActivity.class);
            intent.setData(uri);
            start(context, intent, displayName(context, uri), mime);
        }
    }

    /** The name the other app shows for this file: the address itself says nothing. */
    private static String displayName(final Context context, final Uri uri) {
        try (final android.database.Cursor cursor =
                context.getContentResolver()
                        .query(
                                uri,
                                new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } catch (final RuntimeException e) {
            // no name then
        }
        return context.getString(R.string.velo_doc_title);
    }

    private static void start(
            final Context context, final Intent intent, final String name, final String mime) {
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_MIME, mime);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Nullable
    private static File copyForPreview(final Context context, final Uri uri) {
        final File folder = new File(context.getCacheDir(), PREVIEW_FOLDER);
        final File[] old = folder.listFiles();
        if (old != null) {
            for (final File file : old) {
                file.delete();
            }
        }
        if (!folder.isDirectory() && !folder.mkdirs()) {
            return null;
        }
        final File copy = new File(folder, "preview");
        try (final InputStream in = context.getContentResolver().openInputStream(uri);
                final OutputStream out = new FileOutputStream(copy)) {
            if (in == null) {
                return null;
            }
            in.transferTo(out);
            return copy;
        } catch (final IOException | SecurityException e) {
            copy.delete();
            return null;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MaterialToolbar toolbar;
    private WebView webView;
    private ViewPager2 pager;
    @Nullable private DocClient client;
    @Nullable private File file;
    private String name;
    private String mime;
    private int pdfPages = 0;
    private boolean failed = false;
    private boolean closed = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_velo_document);
        toolbar = findViewById(R.id.velo_doc_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        Activities.setStatusAndNavigationBarColors(this, findViewById(R.id.velo_doc_root));

        final Intent intent = getIntent();
        final String path = intent.getStringExtra(EXTRA_PATH);
        file = path == null ? null : new File(path);
        name = intent.getStringExtra(EXTRA_NAME);
        mime = intent.getStringExtra(EXTRA_MIME);
        if (name == null) {
            name = file != null ? file.getName() : "";
        }
        if (getSupportActionBar() != null) {
            // files received without a name are called after their hash: nothing to read there
            getSupportActionBar()
                    .setTitle(
                            name.matches("[A-Za-z0-9]{32,}(\\.[A-Za-z0-9]+)?")
                                    ? getString(R.string.velo_doc_title)
                                    : name);
        }

        pager = findViewById(R.id.velo_doc_pager);
        findViewById(R.id.velo_doc_save).setOnClickListener(v -> askSave());
        worker.execute(this::load);
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {}

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.velo_document, menu);
        // when a file can't be shown the big button in the middle does this
        menu.findItem(R.id.action_save).setVisible(file != null && !failed);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.action_save) {
            askSave();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        // the worker may still be connecting: it closes the client itself, see connect()
        synchronized (this) {
            closed = true;
            if (client != null) {
                client.close();
                client = null;
            }
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private ParcelFileDescriptor openInput() throws IOException {
        if (file != null) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        final ParcelFileDescriptor descriptor =
                getContentResolver().openFileDescriptor(getIntent().getData(), "r");
        if (descriptor == null) {
            throw new IOException("no descriptor");
        }
        return descriptor;
    }

    private DocClient connect() throws IOException {
        final DocClient connected = DocClient.connect(this);
        synchronized (this) {
            if (closed) {
                connected.close();
                throw new IOException("closed");
            }
            if (client != null) {
                client.close();
            }
            client = connected;
        }
        return connected;
    }

    @Nullable
    private synchronized DocClient client() {
        return client;
    }

    private void load() {
        try {
            final DocClient connected = connect();
            final String html;
            try (final ParcelFileDescriptor input = openInput()) {
                html = connected.convert(input, name, mime);
            }
            runOnUiThread(() -> showHtml(html));
        } catch (final DocClient.Failed e) {
            if (e.status == DocConverter.IS_PDF) {
                loadPdf();
            } else {
                showFailure(e.status);
            }
        } catch (final IOException | RuntimeException | OutOfMemoryError e) {
            // an empty pipe or a dead service: the file made the reader crash
            showFailure(DocConverter.UNREADABLE);
        }
    }

    private void loadPdf() {
        try (final ParcelFileDescriptor input = openInput()) {
            final DocClient connected = client();
            if (connected == null) {
                return;
            }
            final int pages = connected.pdfInfo(input).pages();
            runOnUiThread(() -> showPdf(pages));
        } catch (final DocClient.Failed e) {
            showFailure(e.status);
        } catch (final IOException | RuntimeException e) {
            showFailure(DocConverter.UNREADABLE);
        }
    }

    private void showFailure(final int status) {
        final int text =
                switch (status) {
                    case DocConverter.UNSUPPORTED -> R.string.velo_doc_unsupported;
                    case DocConverter.PROTECTED -> R.string.velo_doc_protected;
                    case DocConverter.TOO_BIG -> R.string.velo_doc_too_big;
                    default -> R.string.velo_doc_unreadable;
                };
        runOnUiThread(
                () -> {
                    if (isDestroyed()) {
                        return;
                    }
                    failed = true;
                    invalidateOptionsMenu();
                    findViewById(R.id.velo_doc_progress).setVisibility(View.GONE);
                    findViewById(R.id.velo_doc_message_box).setVisibility(View.VISIBLE);
                    ((android.widget.TextView) findViewById(R.id.velo_doc_message)).setText(text);
                    findViewById(R.id.velo_doc_save)
                            .setVisibility(file != null ? View.VISIBLE : View.GONE);
                });
    }

    private void showHtml(final String body) {
        if (isDestroyed()) {
            return;
        }
        findViewById(R.id.velo_doc_progress).setVisibility(View.GONE);
        webView = new WebView(this);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setBlockNetworkLoads(true);
        settings.setBlockNetworkImage(true);
        settings.setSaveFormData(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
        webView.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            final WebView view, final WebResourceRequest request) {
                        return true;
                    }

                    @Override
                    public WebResourceResponse shouldInterceptRequest(
                            final WebView view, final WebResourceRequest request) {
                        final String scheme = request.getUrl().getScheme();
                        if ("data".equals(scheme)) {
                            return null;
                        }
                        return new WebResourceResponse(
                                "text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                    }

                    @Override
                    public boolean onRenderProcessGone(
                            final WebView view, final RenderProcessGoneDetail detail) {
                        // without this the whole app would go with the page
                        ((ViewGroup) findViewById(R.id.velo_doc_content)).removeView(view);
                        view.destroy();
                        webView = null;
                        showFailure(DocConverter.UNREADABLE);
                        return true;
                    }
                });
        ((ViewGroup) findViewById(R.id.velo_doc_content))
                .addView(
                        webView,
                        0,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadDataWithBaseURL(
                "about:blank", PAGE_HEAD + body + PAGE_TAIL, "text/html", "utf-8", null);
    }

    private void showPdf(final int pages) {
        if (isDestroyed()) {
            return;
        }
        pdfPages = pages;
        findViewById(R.id.velo_doc_progress).setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new PageAdapter());
        pager.registerOnPageChangeCallback(
                new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(final int position) {
                        toolbar.setSubtitle(
                                getString(R.string.velo_doc_page, position + 1, pdfPages));
                    }
                });
        toolbar.setSubtitle(getString(R.string.velo_doc_page, 1, pdfPages));
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final PhotoView photo;
            int bound = -1;

            Holder(final PhotoView photo) {
                super(photo);
                this.photo = photo;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final PhotoView photo = new PhotoView(parent.getContext());
            photo.setLayoutParams(
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            return new Holder(photo);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, final int position) {
            holder.bound = position;
            holder.photo.setImageDrawable(null);
            final int width = Math.min(2 * getResources().getDisplayMetrics().widthPixels, 2400);
            if (worker.isShutdown()) {
                return;
            }
            worker.execute(
                    () -> {
                        if (holder.bound != position) {
                            return; // scrolled away already
                        }
                        final Bitmap bitmap = renderPage(position, width);
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed()) {
                                        return;
                                    }
                                    if (holder.bound == position && bitmap != null) {
                                        holder.photo.setImageBitmap(bitmap);
                                    } else if (bitmap == null) {
                                        Toast.makeText(
                                                        VeloDocumentActivity.this,
                                                        R.string.velo_doc_unreadable,
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                    }
                                });
                    });
        }

        @Override
        public void onViewRecycled(@NonNull final Holder holder) {
            holder.bound = -1;
            holder.photo.setImageDrawable(null);
        }

        @Override
        public int getItemCount() {
            return pdfPages;
        }
    }

    /** One page, connecting again if a previous page made the reader crash. */
    @Nullable
    private Bitmap renderPage(final int page, final int width) {
        for (int attempt = 0; attempt < 2; ++attempt) {
            final DocClient connected = client();
            if (connected == null) {
                return null;
            }
            try (final ParcelFileDescriptor input = openInput()) {
                return connected.pdfPage(input, page, width, DocService.MODE_WIDTH);
            } catch (final DocClient.Failed | OutOfMemoryError e) {
                return null;
            } catch (final IOException | RuntimeException e) {
                try {
                    connect();
                } catch (final IOException failed) {
                    return null;
                }
            }
        }
        return null;
    }

    private void askSave() {
        if (file == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_save_to_downloads)
                .setMessage(R.string.save_to_downloads_warning)
                .setPositiveButton(R.string.confirm, (dialog, which) -> save())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void save() {
        if (xmppConnectionService == null || file == null) {
            return;
        }
        xmppConnectionService.copyAttachmentToDownloadsFolder(
                file,
                new UiCallback<>() {
                    @Override
                    public void success(final Integer object) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        VeloDocumentActivity.this,
                                                        R.string.save_to_downloads_success,
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }

                    @Override
                    public void error(final int errorCode, final Integer object) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        VeloDocumentActivity.this,
                                                        object,
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }

                    @Override
                    public void userInputRequired(final PendingIntent pi, final Integer object) {}
                });
    }
}
