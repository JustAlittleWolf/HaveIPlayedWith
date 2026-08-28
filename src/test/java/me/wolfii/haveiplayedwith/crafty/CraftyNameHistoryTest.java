package me.wolfii.haveiplayedwith.crafty;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftyNameHistoryTest {
    private static final UUID DINNERBONE = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
    private static final UUID CURRENT_GORMAN = UUID.fromString("15cf0fd1-f5c4-46b3-bf00-75ab04d66386");

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

    @Test
    void nameOnTheListIsNotEnoughIfTheyHadAlreadyRenamed() {
        List<CraftyNameHistory.Entry> history = List.of(
            new CraftyNameHistory.Entry("GormanRoddy", Instant.parse("2026-02-16T22:45:47Z")),
            new CraftyNameHistory.Entry("narcosul", Instant.parse("2025-08-06T08:52:48Z")),
            new CraftyNameHistory.Entry("DetectiveHolmes", null)
        );
        Instant whileDinnerboneHeldIt = Instant.parse("2026-01-07T12:00:00Z");
        assertFalse(CraftyNameHistory.heldNameAt(history, "GormanRoddy", whileDinnerboneHeldIt));
        assertTrue(CraftyNameHistory.heldNameAt(history, "GormanRoddy", Instant.parse("2026-03-01T00:00:00Z")));
    }

    @Test
    void emptyHistoryDoesNotCountAsHavingHeldTheName() {
        assertFalse(CraftyNameHistory.heldNameAt(List.of(), "Steve", Instant.parse("2020-01-01T00:00:00Z")));
    }

    @Test
    void holderAtPicksTheAccountThatHeldTheNameThen() {
        CraftyPlayer dinnerbone = new CraftyPlayer(
            DINNERBONE,
            "Dinnerbone",
            List.of(
                new CraftyNameHistory.Entry("Dinnerbone", Instant.parse("2026-01-07T18:54:24Z")),
                new CraftyNameHistory.Entry("GormanRoddy", Instant.parse("2026-01-07T07:47:48Z")),
                new CraftyNameHistory.Entry("Dinnerbone", null)
            ),
            true
        );
        CraftyPlayer current = new CraftyPlayer(
            CURRENT_GORMAN,
            "GormanRoddy",
            List.of(
                new CraftyNameHistory.Entry("GormanRoddy", Instant.parse("2026-02-16T22:45:47Z")),
                new CraftyNameHistory.Entry("narcosul", Instant.parse("2025-08-06T08:52:48Z")),
                new CraftyNameHistory.Entry("DetectiveHolmes", null)
            ),
            true
        );
        List<CraftyPlayer> owners = List.of(current, dinnerbone);
        assertEquals(
            DINNERBONE,
            CraftyNameHistory.holderAt(owners, "GormanRoddy", Instant.parse("2026-01-07T12:00:00Z")).orElseThrow().uuid()
        );
        assertEquals(
            CURRENT_GORMAN,
            CraftyNameHistory.holderAt(owners, "GormanRoddy", Instant.parse("2026-03-01T00:00:00Z")).orElseThrow().uuid()
        );
        assertTrue(CraftyNameHistory.holderAt(owners, "GormanRoddy", Instant.parse("2025-01-01T00:00:00Z")).isEmpty());
    }
}
