package me.wolfii.haveiplayedwith.observe;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calendar minute a player was last queued, kept per player so a new minute
 * does not re-offer everyone at once.
 */
final class NotedMinutes {
    private final ConcurrentHashMap<UUID, Long> minutes = new ConcurrentHashMap<>();

    boolean noted(UUID uuid, long minute) {
        Long last = minutes.get(uuid);
        return last != null && last == minute;
    }

    void note(UUID uuid, long minute) {
        minutes.put(uuid, minute);
    }

    void pruneBefore(long minute) {
        minutes.values().removeIf(seen -> seen < minute);
    }

    void clear() {
        minutes.clear();
    }
}
