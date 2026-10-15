package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aFullQueueLeavesThePlayerUnnoted() {
        NotedMinutes noted = new NotedMinutes();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        assertTrue(noted.remember(queue, first, 10, "first"));
        assertTrue(noted.noted(first, 10));
        assertEquals("first", queue.peek());
        assertFalse(noted.remember(queue, second, 10, "second"));
        assertFalse(noted.noted(second, 10));
        assertTrue(noted.remember(queue, first, 10, "again"));
        assertEquals(1, queue.size());
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
