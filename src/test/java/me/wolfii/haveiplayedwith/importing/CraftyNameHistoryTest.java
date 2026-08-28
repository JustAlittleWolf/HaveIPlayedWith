package me.wolfii.haveiplayedwith.importing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftyNameHistoryTest {
    @Test
    void originalNameUntilFirstChange() {
        List<CraftyNameHistory.Entry> history = List.of(
            new CraftyNameHistory.Entry("Dinnerbone", Instant.parse("2026-01-07T18:54:24Z")),
            new CraftyNameHistory.Entry("GormanRoddy", Instant.parse("2026-01-07T07:47:48Z")),
            new CraftyNameHistory.Entry("Dinnerbone", null)
        );
        assertTrue(CraftyNameHistory.heldNameAt(history, "Dinnerbone", Instant.parse("2020-01-01T00:00:00Z")));
        assertTrue(CraftyNameHistory.heldNameAt(history, "GormanRoddy", Instant.parse("2026-01-07T12:00:00Z")));
        assertFalse(CraftyNameHistory.heldNameAt(history, "GormanRoddy", Instant.parse("2025-01-01T00:00:00Z")));
        assertTrue(CraftyNameHistory.heldNameAt(history, "Dinnerbone", Instant.parse("2026-01-08T00:00:00Z")));
    }
}
