package me.wolfii.haveiplayedwith.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, for the current client run, which play minute was saved and which
 * of those minutes already counted as nearby. A minute counts as nearby when
 * that player was within range on any tick of that minute.
 */
final class PresenceMinutes {
    private final ConcurrentHashMap<UUID, Long> nearbyMinute = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> nearbySaved = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> creditedMinute = new ConcurrentHashMap<>();

    void markNearby(UUID uuid, long minute) {
        nearbyMinute.put(uuid, minute);
    }

    boolean wasNearby(UUID uuid, long minute) {
        Long marked = nearbyMinute.get(uuid);
        return marked != null && marked == minute;
    }

    void clear() {
        nearbyMinute.clear();
        nearbySaved.clear();
        creditedMinute.clear();
    }

    void prune(long keepAfter) {
        nearbyMinute.values().removeIf(minute -> minute < keepAfter);
        nearbySaved.values().removeIf(minute -> minute < keepAfter);
        creditedMinute.values().removeIf(minute -> minute < keepAfter);
    }

    /** @return {@code false} when this uuid was already credited for {@code minute} */
    boolean markCredited(UUID uuid, long minute) {
        Long previous = creditedMinute.put(uuid, minute);
        return previous == null || previous != minute;
    }

    /**
     * @param seenNearby nearby flag captured when the sighting was queued, kept so a
     *     disconnect can clear the maps without dropping that sample
     * @return whether the play minute being saved should also count as nearby
     */
    boolean nearbyForCredit(UUID uuid, long minute, boolean seenNearby) {
        if (!seenNearby && !wasNearby(uuid, minute)) {
            return false;
        }
        return claimNearby(uuid, minute);
    }

    /** Credited players who came into range after their play minute was saved. */
    List<UUID> lateNearby(long minute) {
        List<UUID> late = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : nearbyMinute.entrySet()) {
            if (entry.getValue() != minute) {
                continue;
            }
            UUID uuid = entry.getKey();
            Long credited = creditedMinute.get(uuid);
            if (credited == null || credited != minute) {
                continue;
            }
            if (claimNearby(uuid, minute)) {
                late.add(uuid);
            }
        }
        return late;
    }

    private boolean claimNearby(UUID uuid, long minute) {
        Long previous = nearbySaved.put(uuid, minute);
        return previous == null || previous != minute;
    }
}
