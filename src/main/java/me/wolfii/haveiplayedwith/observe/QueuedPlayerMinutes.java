package me.wolfii.haveiplayedwith.observe;

import java.util.UUID;
import java.util.HashMap;

/**
 * Last calendar minute queued for each player. Accessed only on the client thread;
 * separate instances let play, AFK, and nearby minutes be queued independently.
 */
final class QueuedPlayerMinutes {
    private final HashMap<UUID, Long> minutes = new HashMap<>();

    boolean wasQueued(UUID uuid, long minute) {
        Long last = minutes.get(uuid);
        return last != null && last == minute;
    }

    void markQueued(UUID uuid, long minute) {
        minutes.put(uuid, minute);
    }

    void pruneBefore(long minute) {
        minutes.values().removeIf(seen -> seen < minute);
    }

    void clear() {
        minutes.clear();
    }
}
