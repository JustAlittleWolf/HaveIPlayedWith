package me.wolfii.haveiplayedwith.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Oldest-first set of player rows waiting to be written. Used only on the
 * database thread. Queueing a player who is already waiting keeps that place
 * and does not add a second row. The queue never grows past {@link #MAX_PENDING};
 * the oldest id is handed back so it can be written immediately.
 */
final class WriteQueue {
    static final int MAX_PENDING = 500;

    private final LinkedHashSet<UUID> pending = new LinkedHashSet<>();

    int size() {
        return pending.size();
    }

    /**
     * @return the oldest queued player when accepting {@code uuid} would pass
     *     {@link #MAX_PENDING}, otherwise empty
     */
    Optional<UUID> add(UUID uuid) {
        if (!pending.add(uuid)) {
            return Optional.empty();
        }
        if (pending.size() <= MAX_PENDING) {
            return Optional.empty();
        }
        return Optional.of(pending.removeFirst());
    }

    Optional<UUID> poll() {
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pending.removeFirst());
    }

    List<UUID> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<UUID> all = new ArrayList<>(pending);
        pending.clear();
        return all;
    }
}
