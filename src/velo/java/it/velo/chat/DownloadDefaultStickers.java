package it.velo.chat;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import eu.siacs.conversations.velo.Log;

import androidx.core.app.NotificationCompat;

import com.google.common.io.ByteStreams;

import io.ipfs.cid.Cid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.FileUtils;
import eu.siacs.conversations.utils.MimeUtils;

public class DownloadDefaultStickers extends Service {

	public static final String DONE = "velo_stickers_done";
	private static final int NOTIFICATION_ID = 20;
	private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
	private DatabaseBackend mDatabaseBackend;
	private NotificationManager notificationManager;
	private File mStickerDir;
	private OkHttpClient http = null;
	private String server = null;
	private HashSet<Uri> pendingPacks = new HashSet<Uri>();

	@Override
	public void onCreate() {
		mDatabaseBackend = DatabaseBackend.getInstance(getBaseContext());
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mStickerDir = stickerDir();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// Velo: only the stickers on our own server, through Tor, whatever the caller asked for
		if (http == null) {
			for (final Account account : mDatabaseBackend.getAccounts()) {
				if (account.isEnabled()) {
					server = account.getServer();
					http = eu.siacs.conversations.velo.VeloUpdater.client(server);
					break;
				}
			}
		}
		if (http == null) {
			stopSelf();
			return START_NOT_STICKY;
		}
		synchronized(pendingPacks) {
			pendingPacks.add(Uri.parse("index.json"));
		}
		if (RUNNING.compareAndSet(false, true)) {
			new Thread(() -> {
				try {
					download();
				} catch (final Exception e) {
					Log.d(Config.LOGTAG, "unable to download stickers", e);
				}
				stopForeground(true);
				RUNNING.set(false);
				stopSelf();
			}).start();
			return START_STICKY;
		} else {
			Log.d(Config.LOGTAG, "DownloadDefaultStickers. ignoring start command because already running");
		}
		return START_NOT_STICKY;
	}

	/** Most bytes one sticker may have; Signal stickers are well below this. */
	private static final long MAX_STICKER_BYTES = 1024 * 1024;
	/** Most bytes of a pack's JSON description, and most stickers taken from one pack. */
	private static final long MAX_PACK_JSON_BYTES = 2 * 1024 * 1024;
	private static final int MAX_STICKERS_PER_PACK = 500;

	/**
	 * A file or folder name from the pack JSON, made safe to use as one path component: no
	 * separators, no control characters, no leading dots ("..", hidden files), bounded length.
	 * Null if nothing usable is left. The JSON comes from a server and, through it, from the
	 * pack's author - it must never choose where in our private storage a file is written.
	 */
	static String safePathComponent(final String raw) {
		if (raw == null) return null;
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			final char c = raw.charAt(i);
			if (c == '/' || c == '\\' || c == ':' || c < 0x20 || c == 0x7f) {
				sb.append('_');
			} else {
				sb.append(c);
			}
		}
		String name = sb.toString().trim();
		while (name.startsWith(".")) name = name.substring(1);
		if (name.length() > 64) name = name.substring(0, 64);
		name = name.trim();
		return name.isEmpty() ? null : name;
	}

	/** File extension for the image types a sticker may have, or null for anything else. */
	static String stickerExtension(final String contentType) {
		if (contentType == null) return null;
		final String type = contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
		switch (type) {
			case "image/png":
			case "image/apng":
				return "png";
			case "image/webp":
				return "webp";
			case "image/gif":
				return "gif";
			case "image/jpeg":
				return "jpg";
			default:
				return null;
		}
	}

	/** Whether {@code file} lies inside {@code dir}, after resolving links and "..". */
	static boolean isInside(final File dir, final File file) throws java.io.IOException {
		final String base = dir.getCanonicalPath() + File.separator;
		return file.getCanonicalPath().startsWith(base);
	}

	private void oneSticker(JSONObject sticker) throws Exception {
		// Velo: only our own server's sticker paths, through VeloUpdater (Tor, pinned)
		final String path = sticker.optString("url", "");
		final String pack = safePathComponent(sticker.optString("pack", null));
		final String name = safePathComponent(sticker.optString("name", null));
		if (!path.matches("[a-z0-9_-]+/[A-Za-z0-9_-]+[.]png")
				|| pack == null || !pack.matches("[A-Za-z0-9_-]+")
				|| name == null || !name.matches("[A-Za-z0-9_-]+")) {
			throw new java.io.IOException("bad sticker entry");
		}
		final okhttp3.HttpUrl url = eu.siacs.conversations.velo.VeloUpdater.stickerUrl(server, path);
		final File packDir = new File(mStickerDir, pack);
		final File file = new File(packDir, name + ".png");
		final File tmp = new File(packDir, "." + name + ".part");
		if (!isInside(mStickerDir, file) || !isInside(mStickerDir, tmp)) {
			throw new java.io.IOException("sticker outside of the sticker directory");
		}
		try (Response r = http.newCall(new Request.Builder().url(url).build()).execute()) {
			if (!r.isSuccessful() || r.body() == null) {
				throw new java.io.IOException("HTTP " + r.code());
			}
			packDir.mkdirs();
			try (OutputStream os = new FileOutputStream(tmp)) {
				final long copied = ByteStreams.copy(ByteStreams.limit(r.body().byteStream(), MAX_STICKER_BYTES + 1), os);
				if (copied > MAX_STICKER_BYTES) {
					throw new java.io.IOException("sticker too large");
				}
			} catch (final Exception e) {
				// never leave a partial file behind: rescanStickers would list it
				tmp.delete();
				throw e;
			}
		}
		if (!tmp.renameTo(file)) {
			tmp.delete();
			throw new java.io.IOException("unable to store sticker");
		}

		// The cids are what the file's bytes hash to - never the ones the JSON claims, which
		// could bind this file to another sticker's (or any file's) content id.
		final Cid[] cids;
		try (java.io.InputStream is = new java.io.FileInputStream(file)) {
			cids = eu.siacs.conversations.utils.CryptoHelper.cid(is, new String[]{"SHA-256", "SHA-1", "SHA-512"});
		}
		for (final Cid cid : cids) {
			mDatabaseBackend.saveCid(cid, file, url.toString());
		}

		try {
			File copyright = new File(packDir, "copyright.txt");
			if (!isInside(mStickerDir, copyright)) return;
			OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(copyright, true), "utf-8");
			w.write(pack);
			w.write('/');
			w.write(name);
			w.write(": ");
			w.write(sticker.optString("copyright", "").replace('\n', ' ').replace('\r', ' '));
			w.write('\n');
			w.close();
		} catch (final Exception e) { }
	}

	private void download() throws Exception {
		Uri jsonUri;
		synchronized(pendingPacks) {
			if (pendingPacks.iterator().hasNext()) {
				jsonUri = pendingPacks.iterator().next();
			} else {
				return;
			}
		}

		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getBaseContext(), "backup");
		mBuilder.setContentTitle(getString(R.string.velo_stickers_downloading))
				.setSmallIcon(R.drawable.ic_archive_24dp)
				.setProgress(1, 0, false);
		startForeground(NOTIFICATION_ID, mBuilder.build());

		JSONArray stickers;
		try (Response r = http.newCall(new Request.Builder()
				.url(eu.siacs.conversations.velo.VeloUpdater.stickerUrl(server, jsonUri.toString())).build()).execute()) {
			if (!r.isSuccessful() || r.body() == null) {
				throw new java.io.IOException("HTTP " + r.code());
			}
			final byte[] json = ByteStreams.toByteArray(ByteStreams.limit(r.body().byteStream(), MAX_PACK_JSON_BYTES + 1));
			if (json.length > MAX_PACK_JSON_BYTES) {
				throw new java.io.IOException("sticker index too large");
			}
			stickers = new JSONArray(new String(json, java.nio.charset.StandardCharsets.UTF_8));
		}
		int failed = 0;

		final Progress progress = new Progress(mBuilder, 1, 0);
		for (int i = 0; i < Math.min(stickers.length(), MAX_STICKERS_PER_PACK); i++) {
			try {
				oneSticker(stickers.getJSONObject(i));
			} catch (final Exception e) {
				failed++;
				Log.d(Config.LOGTAG, "unable to download a sticker", e);
			}

			final int percentage = i * 100 / stickers.length();
			notificationManager.notify(NOTIFICATION_ID, progress.build(percentage));
		}

		if (failed == 0) {
			// Velo: asked again at the next start until they are all here
			PreferenceManager.getDefaultSharedPreferences(getBaseContext()).edit().putBoolean(DONE, true).apply();
		}
		synchronized(pendingPacks) {
			pendingPacks.remove(jsonUri);
		}
		download();
	}

	private File stickerDir() {
		/*
		SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		final String dir = p.getString("sticker_directory", "Stickers");
		if (dir.startsWith("content://")) {
			Uri uri = Uri.parse(dir);
			uri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
			return new File(FileUtils.getPath(getBaseContext(), uri));
		} else {
			return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/Velo" + "/" + dir);
		}
		*/
		return StickersMigration.getStickersDir(getApplicationContext());
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private static class Progress {
		private final NotificationCompat.Builder builder;
		private final int max;
		private final int count;

		private Progress(NotificationCompat.Builder builder, int max, int count) {
			this.builder = builder;
			this.max = max;
			this.count = count;
		}

		private Notification build(int percentage) {
			builder.setProgress(max * 100, count * 100 + percentage, false);
			return builder.build();
		}
	}
}
