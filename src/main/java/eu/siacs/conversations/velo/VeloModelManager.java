package eu.siacs.conversations.velo;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.http.HttpConnectionManager;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Velo: traduzione dei messaggi OFFLINE. Scarica il modello di lingua dal server dell'account
 * (stesso posto e stesso certificato inchiodato degli aggiornamenti, {@link VeloPinning}) la
 * PRIMA volta che serve, lo scompatta, ne verifica lo SHA-256 e scrive il {@code config.yml} per
 * il motore {@link VeloTranslate}. Dopo resta sul telefono in {@code filesDir/velo-models/<dir>/},
 * quindi l'APK non lo porta dentro e resta leggero.
 *
 * <p>Il server pubblica {@code https://<server>:5443/velo-update/models/velo-models.json}: per ogni
 * direzione ({@code enit}, {@code iten}) la lista dei file {@code {gz, local, size, sha256}} dove
 * {@code sha256}/{@code size} sono del file DECOMPRESSO.
 */
public final class VeloModelManager {

    private static final int PORT = 5443;
    private static final String MANIFEST = "velo-models.json";

    /** Avanzamento 0..100 del download del modello. */
    public interface Progress {
        void onProgress(int percent);
    }

    private VeloModelManager() {}

    private static OkHttpClient client(final Account account) {
        return HttpConnectionManager.newBuilder(true, false) // Velo: scarica il modello DENTRO Tor
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .addNetworkInterceptor(VeloPinning.interceptor(account))
                .build();
    }

    private static HttpUrl url(final Account account, final String file) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(account.getServer())
                .port(PORT)
                .addPathSegment("velo-update")
                .addPathSegment("models")
                .addPathSegment(file)
                .build();
    }

    /** true se il modello per questa direzione e' gia' scaricato sul telefono. */
    public static boolean isReady(final Context context, final String dir) {
        final File base = new File(context.getFilesDir(), "velo-models/" + dir);
        return new File(base, "config.yml").isFile()
                && new File(base, "model.intgemm.alphas.bin").length() > 0
                && new File(base, "vocab.spm").length() > 0
                && new File(base, "lex.s2t.bin").length() > 0;
    }

    /**
     * Ritorna il path del {@code config.yml} pronto per {@link VeloTranslate#nativeTranslateOnce},
     * scaricando il modello al primo uso. Lavoro di rete e disco: NON sul thread principale.
     */
    public static File ensureModel(final Context context, final Account account, final String dir,
                                   final Progress progress) throws IOException {
        final File base = new File(context.getFilesDir(), "velo-models/" + dir);
        final File config = new File(base, "config.yml");
        if (isReady(context, dir)) {
            return config;
        }
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("Non riesco a creare la cartella del modello");
        }
        final JSONArray files;
        try {
            final Request request = new Request.Builder().url(url(account, MANIFEST)).build();
            final JSONObject manifest;
            try (final Response response = client(account).newCall(request).execute()) {
                final ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    throw new IOException("HTTP " + response.code());
                }
                manifest = new JSONObject(body.string());
            }
            files = manifest.getJSONObject(dir).getJSONArray("files");
        } catch (final JSONException e) {
            throw new IOException("Elenco modelli non valido", e);
        }
        final int n = files.length();
        for (int i = 0; i < n; i++) {
            final String gz;
            final String local;
            final String sha;
            try {
                final JSONObject f = files.getJSONObject(i);
                gz = f.getString("gz");
                local = f.getString("local");
                sha = f.getString("sha256").toLowerCase(Locale.ROOT);
            } catch (final JSONException e) {
                throw new IOException("Voce modello non valida", e);
            }
            if (!VeloKnownModels.isServerFileName(gz) || !VeloKnownModels.isTranslationFile(local, sha)) {
                throw new IOException("Modello non riconosciuto");
            }
            final File out = new File(base, local);
            final File tmp = new File(base, local + ".part");
            final int index = i;
            downloadGunzipVerify(account, gz, tmp, sha, percent ->
                    progress.onProgress((int) ((index * 100L + percent) / n)));
            if (out.exists() && !out.delete()) {
                tmp.delete();
                throw new IOException("Non riesco a sostituire " + local);
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                throw new IOException("Non riesco a salvare " + local);
            }
        }
        writeConfig(config);
        progress.onProgress(100);
        return config;
    }

    private static void downloadGunzipVerify(final Account account, final String gz, final File out,
                                             final String expectedSha, final Progress progress)
            throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        final Request request = new Request.Builder().url(url(account, gz)).build();
        try (final Response response = client(account).newCall(request).execute()) {
            final ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            final long totalGz = body.contentLength();
            try (final CountingInputStream counting = new CountingInputStream(body.byteStream());
                 final GZIPInputStream in = new GZIPInputStream(counting);
                 final OutputStream os = new FileOutputStream(out)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                int last = -1;
                while ((read = in.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    if (totalGz > 0) {
                        final int percent = (int) (counting.count * 100 / totalGz);
                        if (percent != last) {
                            last = percent;
                            progress.onProgress(percent);
                        }
                    }
                }
            }
        }
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        if (!hex.toString().equals(expectedSha)) {
            out.delete();
            throw new IOException("Il file scaricato non corrisponde (checksum)");
        }
    }

    private static void writeConfig(final File config) throws IOException {
        final String yml = "relative-paths: true\n"
                + "models:\n  - model.intgemm.alphas.bin\n"
                + "vocabs:\n  - vocab.spm\n  - vocab.spm\n"
                + "shortlist:\n    - lex.s2t.bin\n    - false\n"
                + "beam-size: 1\n"
                + "normalize: 1.0\n"
                + "word-penalty: 0\n"
                + "max-length-break: 128\n"
                + "mini-batch-words: 1024\n"
                + "workspace: 128\n"
                + "max-length-factor: 2.5\n"
                + "gemm-precision: int8shiftAlphaAll\n";
        try (final OutputStream os = new FileOutputStream(config)) {
            os.write(yml.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Conta i byte COMPRESSI letti, per l'avanzamento del download. */
    private static final class CountingInputStream extends FilterInputStream {
        long count = 0;

        CountingInputStream(final InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            final int c = super.read();
            if (c != -1) {
                count++;
            }
            return c;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
