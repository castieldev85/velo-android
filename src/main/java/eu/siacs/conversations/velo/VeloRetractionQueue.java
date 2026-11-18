package eu.siacs.conversations.velo;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Velo: "delete for everyone" still to send because a message with a timer ran out. Apps that
 * don't know the timer (Monal...) do honour a retraction, so that's how a timed message also
 * leaves their phone. Kept until the account is online: a retraction can't be queued in the
 * normal outbox, it is deleted from the chat right after it's sent.
 */
public final class VeloRetractionQueue {

    private static final String KEY = "velo_pending_retractions";
    private static final String SEPARATOR = "\u001f";

    private VeloRetractionQueue() {}

    public static synchronized void add(
            final SharedPreferences preferences, final String conversationUuid, final String retractId) {
        final Set<String> pending = new HashSet<>(preferences.getStringSet(KEY, new HashSet<>()));
        pending.add(conversationUuid + SEPARATOR + retractId);
        preferences.edit().putStringSet(KEY, pending).apply();
    }

    /** The pending retractions of these conversations as {uuid, id}; they stay in the queue. */
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

    /** Removes and returns the pending retractions of these conversations, as {uuid, id}. */
    public static synchronized List<String[]> take(
            final SharedPreferences preferences, final Set<String> conversationUuids) {
        final Set<String> pending = new HashSet<>(preferences.getStringSet(KEY, new HashSet<>()));
        final List<String[]> taken = new ArrayList<>();
        for (final String entry : new ArrayList<>(pending)) {
            final String[] parts = entry.split(SEPARATOR, 2);
            if (parts.length == 2 && conversationUuids.contains(parts[0])) {
                taken.add(parts);
                pending.remove(entry);
            }
        }
        if (!taken.isEmpty()) {
            preferences.edit().putStringSet(KEY, pending).apply();
        }
        return taken;
    }
}
