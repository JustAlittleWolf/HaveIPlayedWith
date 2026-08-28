package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSnapshotTest {
    private static final UUID STEVE = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    @Test
    void currentSessionAloneIsNotPriorPlay() {
        assertFalse(snapshot(2, 1, 1).hasPlayedBefore(2));
        assertFalse(snapshot(8, 1, 1).hasPlayedBefore(8));
    }

    @Test
    void yesOnlyWhenPriorMinutesExceedSessionPlusBuffer() {
        assertFalse(snapshot(7, 1, 1).hasPlayedBefore(2));
        assertTrue(snapshot(8, 1, 1).hasPlayedBefore(2));
        assertTrue(snapshot(100, 2, 3).hasPlayedBefore(2));
    }

    @Test
    void anotherSessionCountsAsPlayedBefore() {
        assertTrue(snapshot(2, 2, 1).hasPlayedBefore(2));
    }

    @Test
    void unusedCurrentSessionFallsBackToAnyHistory() {
        assertTrue(snapshot(0, 1, 1).hasPlayedBefore(0));
        assertFalse(snapshot(0, 0, 0).hasPlayedBefore(0));
    }

    private static PlayerSnapshot snapshot(long totalMinutes, int sessionCount, int daysPlayed) {
        return new PlayerSnapshot(
            STEVE,
            "Steve",
            Optional.empty(),
            Optional.empty(),
            totalMinutes,
            sessionCount,
            daysPlayed,
            Optional.of(LocalDate.of(2026, 8, 1)),
            List.of(),
            List.of()
        );
    }
}
