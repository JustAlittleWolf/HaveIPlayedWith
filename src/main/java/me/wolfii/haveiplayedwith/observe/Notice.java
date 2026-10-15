package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.store.PlayerStore;

/**
 * Something the observer queued for the dispatcher. Play sightings leave
 * {@link #record} false and continue through profile lookup. AFK and nearby
 * minutes record themselves.
 */
interface Notice {
    /**
     * @return {@code true} when this notice wrote its own counter
     */
    default boolean record(PlayerStore players) {
        return false;
    }
}
