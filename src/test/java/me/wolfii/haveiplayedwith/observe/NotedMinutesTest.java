package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotedMinutesTest {
    @Test
    void aPlayerIsNotedOncePerMinute() {
        NotedMinutes noted = new NotedMinutes();
        UUID uuid = UUID.randomUUID();
        assertFalse(noted.noted(uuid, 10));
        noted.note(uuid, 10);
        assertTrue(noted.noted(uuid, 10));
        assertFalse(noted.noted(uuid, 11));
    }

    @Test
    void pruneDropsMinutesOlderThanTheCutoff() {
        NotedMinutes noted = new NotedMinutes();
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        noted.note(kept, 40);
        noted.note(dropped, 39);
        noted.pruneBefore(40);
        assertTrue(noted.noted(kept, 40));
        assertFalse(noted.noted(dropped, 39));
    }

    @Test
    void clearForgetsEveryPlayer() {
        NotedMinutes noted = new NotedMinutes();
        UUID uuid = UUID.randomUUID();
        noted.note(uuid, 10);
        noted.clear();
        assertFalse(noted.noted(uuid, 10));
    }
}
