package eu.siacs.conversations.velo;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Velo: "delete on the other phone too" still to send, because this phone was offline when it was
 * asked. The chat is already gone here; the purge leaves as soon as the account is online. It can't
 * wait in the normal outbox: it is deleted from the chat right after it's sent.
 */
public final class VeloPurgeQueue {

    private static final String KEY = "velo_pending_purges";
    private static final String SEPARATOR = "\u001f";

    private VeloPurgeQueue() {}

    public static synchronized void add(
            final SharedPreferences preferences, final String conversationUuid, final long before) {
        final Set<String> pending = new HashSet<>(preferences.getStringSet(KEY, new HashSet<>()));
        pending.add(conversationUuid + SEPARATOR + before);
        preferences.edit().putStringSet(KEY, pending).commit();
    }

    /** The pending purges of these conversations as {uuid, time}; they stay in the queue. */
    public static synchronized List<String[]> peek(
            final SharedPreferences preferences, final Set<String> conversationUuids) {
        final List<String[]> found = new ArrayList<>();
        for (final String entry : preferences.getStringSet(KEY, new HashSet<>())) {
            final String[] parts = entry.split(SEPARATOR, 2);
            if (parts.length == 2 && conversationUuids.contains(parts[0])) {
                found.add(parts);
            }
        }
        return found;
    }

    /** Removes and returns the pending purges of these conversations, as {uuid, time}. */
    public static synchronized List<String[]> take(
            final SharedPreferences preferences, final Set<String> conversationUuids) {
        final List<String[]> taken = peek(preferences, conversationUuids);
        if (!taken.isEmpty()) {
            final Set<String> pending = new HashSet<>(preferences.getStringSet(KEY, new HashSet<>()));
            for (final String[] parts : taken) {
                pending.remove(parts[0] + SEPARATOR + parts[1]);
            }
            preferences.edit().putStringSet(KEY, pending).commit();
        }
        return taken;
    }
}
