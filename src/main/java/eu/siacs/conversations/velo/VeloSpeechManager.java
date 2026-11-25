package eu.siacs.conversations.velo;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.http.HttpConnectionManager;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Velo: dettatura vocale OFFLINE (Vosk). Scarica il modello di riconoscimento vocale dal server
 * dell'account la PRIMA volta che serve (stesso posto e certificato inchiodato degli aggiornamenti,
 * {@link VeloPinning}), ne verifica lo SHA-256, lo scompatta in
 * {@code filesDir/velo-vosk/<dir>/} e ne ritorna il percorso per {@code org.vosk.Model}. Il modello
 * NON sta nell'APK: resta sul telefono dopo il primo download.
 *
 * <p>Il server pubblica {@code https://<server>:5443/velo-update/models/velo-vosk.json}: per ogni
 * lingua {@code {zip, dir, size, sha256}} (sha256/size dello ZIP).
 */
public final class VeloSpeechManager {

    private static final int PORT = 5443;
    private static final String MANIFEST = "velo-vosk.json";

    public interface Progress {
        /** Quanti byte sono arrivati, su quanti. */
        void onDownload(long done, long total);
        /** Download finito, ora scompatta. */
        void onUnpacking();
        /** false quando l'utente ha annullato: il lavoro si ferma. */
        boolean wanted();
    }

    /** L'utente ha annullato: non e' un errore da mostrare. */
    public static final class Cancelled extends IOException {}

    private VeloSpeechManager() {}

    private static OkHttpClient client(final Account account) {
        return HttpConnectionManager.newBuilder(true, false) // Velo: scarica il modello DENTRO Tor
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
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

    /** Scritto per ultimo, a modello intero: una cartella senza non conta, resti di versioni vecchie compresi. */
    private static final String COMPLETE = ".velo-complete";
    private static final Object LOCK = new Object();

    /** true se il modello della lingua e' intero sul telefono. */
    public static boolean isReady(final Context context, final String lang) {
        final String dir = VeloKnownModels.speechDir(lang);
        return dir != null && isDirReady(context, dir);
    }

    private static boolean isDirReady(final Context context, final String dir) {
        final File model = new File(new File(context.getFilesDir(), "velo-vosk"), dir);
        return new File(model, COMPLETE).isFile() && new File(model, "am/final.mdl").isFile();
    }

    /** Il modello di questa lingua non si apre: via solo lui, al prossimo giro si riscarica. */
    public static void discard(final Context context, final String lang) {
        final String dir = VeloKnownModels.speechDir(lang);
        if (dir == null) {
            return;
        }
        synchronized (LOCK) {
            deleteRecursive(new File(new File(context.getFilesDir(), "velo-vosk"), dir));
        }
    }

    /**
     * Velo 0.52: toglie i resti (scompattamento interrotto, cartella a meta' della lingua che si sta
     * scaricando, versioni vecchie) e lascia intatti i modelli interi delle altre lingue.
     */
    private static void clearLeftovers(final File base, final String dir) {
        final File[] children = base.listFiles();
        if (children == null) {
            return;
        }
        for (final File c : children) {
            if (c.getName().equals(dir) || !VeloKnownModels.isSpeechDir(c.getName())) {
                deleteRecursive(c);
            }
        }
    }

    /**
     * Percorso della cartella del modello Vosk per la lingua indicata, scaricandolo al primo uso;
     * se c'e' gia' non tocca la rete. Lavoro di rete e disco: NON sul thread principale. Una
     * dettatura fatta ripartire durante il download aspetta qui invece di scrivere sugli stessi file.
     */
    public static File ensureModel(final Context context, final Account account, final String lang,
                                   final Progress progress) throws IOException {
        synchronized (LOCK) {
            if (!progress.wanted()) {
                throw new Cancelled();
            }
            final File base = new File(context.getFilesDir(), "velo-vosk");
            final String expectedDir = VeloKnownModels.speechDir(lang);
            final String expectedSha = VeloKnownModels.speechSha256(lang);
            if (expectedDir == null || expectedSha == null) {
                throw new IOException("Lingua non disponibile: " + lang);
            }
            if (isDirReady(context, expectedDir)) {
                return new File(base, expectedDir);
            }
            final String zip;
            final String dir;
            final long size;
            final String sha;
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
                final JSONObject m = manifest.optJSONObject(lang);
                if (m == null) {
                    // server non ancora aggiornato con i modelli nuovi
                    throw new IOException("Lingua non ancora disponibile sul server");
                }
                zip = m.getString("zip");
                dir = m.getString("dir");
                size = m.getLong("size");
                sha = m.getString("sha256").toLowerCase(Locale.ROOT);
            } catch (final JSONException e) {
                throw new IOException("Elenco modelli vocali non valido", e);
            }
            if (!VeloKnownModels.isServerFileName(zip)
                    || !expectedDir.equals(dir)
                    || !expectedSha.equals(sha)) {
                throw new IOException("Modello vocale non riconosciuto");
            }
            final File modelDir = new File(base, dir);
            if (isDirReady(context, dir)) {
                return modelDir;
            }

            // Via i resti di download interrotti e di versioni vecchie, non le altre lingue
            if (!base.isDirectory() && !base.mkdirs()) {
                throw new IOException("Non riesco a creare la cartella del modello vocale");
            }
            clearLeftovers(base, dir);
            // Lo ZIP piu' il modello scompattato, che pesa circa il doppio
            if (context.getFilesDir().getUsableSpace() < size * 4) {
                throw new IOException("Spazio insufficiente sul telefono (servono circa "
                        + (size * 4 / (1024 * 1024)) + " MB)");
            }

            // 1) scarica lo ZIP nella cache, verificando lo SHA-256
            final File tmpZip = new File(context.getCacheDir(), "velo-vosk-" + dir + ".zip");
            try {
                downloadVerify(account, zip, tmpZip, size, sha, progress);
            } catch (final Cancelled e) {
                tmpZip.delete();
                throw e;
            }
            progress.onUnpacking();

            // 2) scompatta a parte (lo ZIP ha gia' la cartella <dir>/ in testa) e mette a posto
            //    solo a lavoro finito: un'interruzione non lascia mai un modello a meta'
            final File unpacking = new File(base, ".unpacking");
            try {
                unzip(tmpZip, unpacking);
            } finally {
                tmpZip.delete();
            }
            final File unpacked = new File(unpacking, dir);
            if (!new File(unpacked, "am/final.mdl").isFile()
                    || !new File(unpacked, COMPLETE).createNewFile()
                    || !unpacked.renameTo(modelDir)) {
                deleteRecursive(unpacking);
                deleteRecursive(modelDir);
                throw new IOException("Modello vocale incompleto dopo lo scompattamento");
            }
            deleteRecursive(unpacking);
            return modelDir;
        }
    }

    private static void downloadVerify(final Account account, final String file, final File out,
                                       final long expectedSize, final String expectedSha,
                                       final Progress progress) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        final Request request = new Request.Builder().url(url(account, file)).build();
        try (final Response response = client(account).newCall(request).execute()) {
            final ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            final long total = expectedSize > 0 ? expectedSize : body.contentLength();
            try (final InputStream in = body.byteStream();
                 final OutputStream os = new FileOutputStream(out)) {
                final byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int last = -1;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (!progress.wanted()) {
                        throw new Cancelled();
                    }
                    os.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    if (total > 0) {
                        final int percent = (int) (done * 100 / total);
                        if (percent != last) {
                            last = percent;
                            progress.onDownload(done, total);
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
            throw new IOException("Il modello scaricato non corrisponde (checksum)");
        }
    }

    /** Scompatta lo ZIP sotto {@code base}. Protetto da zip-slip. */
    private static void unzip(final File zipFile, final File base) throws IOException {
        final String baseCanonical = base.getCanonicalPath() + File.separator;
        try (final ZipInputStream zis = new ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            final byte[] buffer = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                final File target = new File(base, entry.getName());
                if (!target.getCanonicalPath().startsWith(baseCanonical)) {
                    throw new IOException("Voce ZIP non valida: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    final File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Non riesco a creare " + parent);
                    }
                    try (final OutputStream os = new FileOutputStream(target)) {
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            os.write(buffer, 0, read);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteRecursive(final File f) {
        if (f == null || !f.exists()) {
            return;
        }
        final File[] children = f.listFiles();
        if (children != null) {
            for (final File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
