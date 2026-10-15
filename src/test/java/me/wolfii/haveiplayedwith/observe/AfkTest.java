package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfkTest {
    @Test
    void sixtySecondsWithoutInputIsAfk() {
        long input = 1_000_000L;
        assertFalse(Afk.inactive(input, input + Afk.AFK_AFTER_MS - 1));
        assertTrue(Afk.inactive(input, input + Afk.AFK_AFTER_MS));
        assertTrue(Afk.inactive(input, input + Afk.AFK_AFTER_MS + 1));
    }
}
