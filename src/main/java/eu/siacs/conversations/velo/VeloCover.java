package eu.siacs.conversations.velo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.Consumer;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.pep.PublishOptions;
import im.conversations.android.xmpp.model.stanza.Iq;

/**
 * Velo 0.47: the cover picture above the profile picture, as on Telegram or Facebook.
 *
 * <p>It travels like the profile picture: one small JPEG in a PEP node of the owner's account,
 * readable by the contacts who see the owner's presence and by nobody else. Like the profile
 * picture it is not end-to-end encrypted - the owner's server can see it. Only Velo shows it;
 * other XMPP apps never ask for the node.
 */
public final class VeloCover {

    public static final String NODE = "urn:velo:cover:0";
    /** 3:1, small enough to cost little over Tor and to stay well under the server's limits. */
    public static final int WIDTH = 1200;
    public static final int HEIGHT = 400;
    private static final int MAX_BYTES = 100 * 1024;

    private VeloCover() {}

    private static File file(final Context context, final Jid jid) {
        final File dir = new File(context.getFilesDir(), "velo-covers");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.d(Config.LOGTAG, "unable to create the cover folder");
        }
        return new File(dir, sha1(jid.asBareJid().toString().getBytes(StandardCharsets.UTF_8)) + ".jpg");
    }

    /** The cover last seen for {@code jid}, or null: shown at once, before asking the server. */
    @Nullable
    public static Bitmap cached(final Context context, final Jid jid) {
        final File file = file(context, jid);
        return file.isFile() ? BitmapFactory.decodeFile(file.getAbsolutePath()) : null;
    }

    /** The cropped picture as a JPEG of at most {@link #WIDTH}x{@link #HEIGHT} and 100 KiB. */
    @Nullable
    public static byte[] encode(final Context context, final Uri uri) {
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= WIDTH) {
                sample *= 2;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            final Bitmap decoded;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(in, null, options);
            }
            if (decoded == null) {
                return null;
            }
            final float scale = Math.min(1f, Math.min(WIDTH / (float) decoded.getWidth(), HEIGHT / (float) decoded.getHeight()));
            final Bitmap bitmap = scale < 1f
                    ? Bitmap.createScaledBitmap(decoded,
                            Math.round(decoded.getWidth() * scale), Math.round(decoded.getHeight() * scale), true)
                    : decoded;
            for (int quality = 85; ; quality -= 10) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
                if (out.size() <= MAX_BYTES || quality <= 35) {
                    return out.toByteArray();
                }
            }
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, e);
            return null;
        }
    }

    /** Publishes {@code jpeg} as the account's cover; {@code done} gets whether the server took it. */
    public static void publish(
            final XmppConnectionService service, final Account account, final byte[] jpeg, final Consumer<Boolean> done) {
        final boolean options = account.getXmppConnection() != null
                && account.getXmppConnection().getFeatures().pepPublishOptions();
        publish(service, account, jpeg, options ? access() : null, true, done);
    }

    private static Bundle access() {
        final Bundle options = PublishOptions.presenceAccess();
        options.putString("pubsub#max_items", "1");
        return options;
    }

    private static void publish(
            final XmppConnectionService service,
            final Account account,
            final byte[] jpeg,
            @Nullable final Bundle options,
            final boolean retry,
            final Consumer<Boolean> done) {
        final Element cover = new Element("cover", NODE);
        cover.setAttribute("type", "image/jpeg");
        cover.setContent(Base64.encodeToString(jpeg, Base64.NO_WRAP));
        final Iq request = service.getIqGenerator().publishElement(NODE, cover, sha1(jpeg), options);
        service.sendIqPacket(account, request, result -> {
            if (result.getType() == Iq.Type.RESULT) {
                write(service, account.getJid(), jpeg);
                done.accept(true);
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                // a node made by an older attempt with other settings: align it, then try again
                service.pushNodeConfiguration(account, NODE, options, new XmppConnectionService.OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        publish(service, account, jpeg, options, false, done);
                    }

                    @Override
                    public void onPushFailed() {
                        done.accept(false);
                    }
                });
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": cover refused " + result.findChild("error"));
                done.accept(false);
            }
        });
    }

    /** Takes the cover away from the server and from this phone. */
    public static void remove(final XmppConnectionService service, final Account account) {
        service.deletePepNode(account, NODE);
        final File file = file(service, account.getJid());
        if (file.isFile() && !file.delete()) {
            Log.d(Config.LOGTAG, "unable to delete the cached cover");
        }
    }

    /**
     * Asks {@code jid}'s server for the current cover. {@code changed} runs, off the main thread,
     * only when it differs from the cached one: the new picture, or null when it was removed.
     */
    public static void fetch(
            final XmppConnectionService service, final Account account, final Jid jid, final Consumer<Bitmap> changed) {
        if (account.getStatus() != Account.State.ONLINE) {
            return;
        }
        final Iq request = new Iq(Iq.Type.GET);
        request.setTo(jid.asBareJid());
        final Element items = request.addChild("pubsub", Namespace.PUBSUB).addChild("items");
        items.setAttribute("node", NODE);
        items.setAttribute("max_items", "1");
        service.sendIqPacket(account, request, response -> {
            final File file = file(service, jid);
            if (response.getType() == Iq.Type.RESULT) {
                final byte[] jpeg = parse(response);
                if (jpeg == null) {
                    forget(file, changed);
                } else if (!file.isFile() || !Arrays.equals(jpeg, read(file))) {
                    write(service, jid, jpeg);
                    changed.accept(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length));
                }
            } else if (response.getType() == Iq.Type.ERROR) {
                final Element error = response.findChild("error");
                // gone, or no longer allowed to see it: drop the copy too. Timeouts keep it.
                if (error != null && (error.hasChild("item-not-found") || error.hasChild("forbidden")
                        || error.hasChild("not-authorized"))) {
                    forget(file, changed);
                }
            }
        });
    }

    @Nullable
    private static byte[] parse(final Iq response) {
        final Element pubsub = response.findChild("pubsub", Namespace.PUBSUB);
        final Element items = pubsub == null ? null : pubsub.findChild("items");
        final Element item = items == null ? null : items.findChild("item");
        final Element cover = item == null ? null : item.findChild("cover", NODE);
        final String content = cover == null ? null : cover.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            final byte[] jpeg = Base64.decode(content.trim(), Base64.DEFAULT);
            // never more than a cover: a bigger payload is not one of ours
            return jpeg.length > 4 * MAX_BYTES ? null : jpeg;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static void forget(final File file, final Consumer<Bitmap> changed) {
        if (file.isFile() && file.delete()) {
            changed.accept(null);
        }
    }

    private static void write(final Context context, final Jid jid, final byte[] jpeg) {
        try (FileOutputStream out = new FileOutputStream(file(context, jid))) {
            out.write(jpeg);
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, e);
        }
    }

    @Nullable
    private static byte[] read(final File file) {
        // not java.nio.file: Velo still runs on Android 7
        try (InputStream in = new java.io.FileInputStream(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (final Exception e) {
            return null;
        }
    }

    private static String sha1(final byte[] data) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            final StringBuilder hex = new StringBuilder();
            for (final byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final Exception e) {
            return String.valueOf(Arrays.hashCode(data));
        }
    }
}
