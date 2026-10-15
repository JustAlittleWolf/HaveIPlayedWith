package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuedPlayerMinutesTest {
    @Test
    void aPlayerIsNotedOncePerMinute() {
        QueuedPlayerMinutes noted = new QueuedPlayerMinutes();
        UUID uuid = UUID.randomUUID();
        assertFalse(noted.wasQueued(uuid, 10));
        noted.markQueued(uuid, 10);
        assertTrue(noted.wasQueued(uuid, 10));
        assertFalse(noted.wasQueued(uuid, 11));
    }

    @Test
    void pruneDropsMinutesOlderThanTheCutoff() {
        QueuedPlayerMinutes noted = new QueuedPlayerMinutes();
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        noted.markQueued(kept, 40);
        noted.markQueued(dropped, 39);
        noted.pruneBefore(40);
        assertTrue(noted.wasQueued(kept, 40));
        assertFalse(noted.wasQueued(dropped, 39));
    }

    @Test
    void clearForgetsEveryPlayer() {
        QueuedPlayerMinutes noted = new QueuedPlayerMinutes();
        UUID uuid = UUID.randomUUID();
        noted.markQueued(uuid, 10);
        noted.clear();
        assertFalse(noted.wasQueued(uuid, 10));
    }
}
