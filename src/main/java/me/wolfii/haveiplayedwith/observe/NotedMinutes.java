package me.wolfii.haveiplayedwith.observe;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
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

    /**
     * Queues {@code item} the first time {@code uuid} is seen in {@code minute}.
     * A full queue leaves the player unnoted so a later tick can retry.
     *
     * @return {@code false} when the item was new and the queue refused it
     */
    <T> boolean remember(BlockingQueue<T> out, UUID uuid, long minute, T item) {
        if (noted(uuid, minute)) {
            return true;
        }
        if (!out.offer(item)) {
            return false;
        }
        note(uuid, minute);
        return true;
    }

    void pruneBefore(long minute) {
        minutes.values().removeIf(seen -> seen < minute);
    }

    void clear() {
        minutes.clear();
    }
}
