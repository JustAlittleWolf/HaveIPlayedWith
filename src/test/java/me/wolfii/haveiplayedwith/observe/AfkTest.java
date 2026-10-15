package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfkTest {
    @Test
    void sixtySecondsWithoutInputIsAfk() {
        long input = 1_000_000L;
        assertFalse(AfkObserver.inactive(input, input + AfkObserver.AFK_AFTER_MS - 1));
        assertTrue(AfkObserver.inactive(input, input + AfkObserver.AFK_AFTER_MS));
        assertTrue(AfkObserver.inactive(input, input + AfkObserver.AFK_AFTER_MS + 1));
    }
}
