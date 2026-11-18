package eu.siacs.conversations.velo;

import android.content.SharedPreferences;

/**
 * Velo: privacy settings that differ from upstream.
 *
 * <p>Read receipts, typing notifications and "last seen" start off but stay switchable. Link
 * previews (the sender's phone would fetch every linked page, revealing its IP to the site) and
 * reactions sent outside the encryption are blocked for good.
 */
public final class VeloPrivacy {

    /** Bumped when a release has to switch settings off again on phones that already had Velo. */
    private static final String MIGRATED_KEY = "velo_privacy_migrated";
    private static final int MIGRATION = 2; // 1: Velo 0.10, 2: Velo 0.14 (simpler settings)

    private VeloPrivacy() {}

    /** Turns the metadata leaks off once on phones that installed an older Velo. */
    public static void migrate(final SharedPreferences preferences) {
        final int done = preferences.getInt(MIGRATED_KEY, 0);
        if (done >= MIGRATION) {
            return;
        }
        final SharedPreferences.Editor editor = preferences.edit();
        if (done < 1) {
            editor.putBoolean("confirm_messages", false)
                    .putBoolean("chat_states", false)
                    .putBoolean("last_activity", false)
                    .putBoolean("send_link_previews", false)
                    .putBoolean("allow_unencrypted_reactions", false);
        }
        // 0.14: these switches left the settings, so they are pinned to the safe value:
        // no images loaded from other sites (IP leak), the in-app viewer (view-once needs it),
        // the foreground service (messages on time)
        editor.putBoolean("load_image_from_any_link", false)
                .putBoolean("internal_meda_viewer", true)
                .putBoolean("enable_foreground_service", true)
                .putInt(MIGRATED_KEY, MIGRATION)
                .apply();
    }
}
