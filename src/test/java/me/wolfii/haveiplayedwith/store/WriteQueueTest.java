package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteQueueTest {
    @Test
    void queuesInOrderAndCoalescesASecondSave() {
        WriteQueue queue = new WriteQueue();
        UUID first = new UUID(1, 0);
        UUID second = new UUID(2, 0);

        assertTrue(queue.add(first).isEmpty());
        assertTrue(queue.add(second).isEmpty());
        assertTrue(queue.add(first).isEmpty());

        assertEquals(2, queue.size());
        assertEquals(first, queue.poll().orElseThrow());
        assertEquals(second, queue.poll().orElseThrow());
        assertTrue(queue.poll().isEmpty());
    }

    @Test
    void capEvictsTheOldestRow() {
        WriteQueue queue = new WriteQueue();
        Optional<UUID> evicted = Optional.empty();
        for (int i = 0; i < WriteQueue.MAX_PENDING + 1; i++) {
            evicted = queue.add(new UUID(i, 1));
        }

        assertEquals(new UUID(0, 1), evicted.orElseThrow());
        assertEquals(WriteQueue.MAX_PENDING, queue.size());
        assertEquals(new UUID(1, 1), queue.poll().orElseThrow());
    }

    @Test
    void drainEmptiesInInsertionOrder() {
        WriteQueue queue = new WriteQueue();
        UUID first = new UUID(1, 2);
        UUID second = new UUID(2, 2);
        queue.add(first);
        queue.add(second);

        assertEquals(List.of(first, second), queue.drain());
        assertEquals(0, queue.size());
        assertTrue(queue.drain().isEmpty());
    }
}
