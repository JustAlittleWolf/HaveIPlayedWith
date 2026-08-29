package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStoreTest {
    @TempDir
    Path temp;

    private PlayerStore open() {
        return new PlayerStore(temp.resolve("store.db"));
    }

    @Test
    void recordsPlayAndFindsPastNames() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
            players.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "world/Survival");
            players.setNote(uuid, "Alex", "builds nice farms");

            List<PlayerSnapshot> byOld = players.findByName("steve");
            assertEquals(1, byOld.size());
            PlayerSnapshot snapshot = byOld.getFirst();
            assertEquals("Alex", snapshot.currentUsername());
            assertEquals(3, snapshot.totalMinutes());
            assertEquals(2, snapshot.daysPlayed());
            assertEquals(2, snapshot.sessionCount());
            assertEquals("builds nice farms", snapshot.note().orElseThrow());
            assertTrue(snapshot.noteTakenAt().isPresent());
            assertEquals(LocalDate.of(2026, 8, 2), snapshot.lastPlayedBeforeToday().orElseThrow());
            assertTrue(snapshot.pastNames().stream().anyMatch(name -> name.username().equals("Steve")));
            assertEquals("hypixel.net", snapshot.mostPlayedServer().orElseThrow().serverId());
            assertEquals(2, snapshot.mostPlayedServer().orElseThrow().minutes());
        }
    }

    @Test
    void notesDoNotCountAsHavingPlayedTogether() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.setNote(uuid, "Ghost", "met them on Discord");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(0, snapshot.totalMinutes());
            assertEquals(0, snapshot.daysPlayed());
            assertEquals(0, snapshot.sessionCount());
            assertTrue(snapshot.names().isEmpty());
            assertTrue(snapshot.note().isPresent());
            assertTrue(snapshot.noteTakenAt().isPresent());
            assertFalse(snapshot.hasPlayed());
            assertEquals(1, players.findByName("ghost").size());
            assertTrue(snapshot.mostPlayedServer().isEmpty());
        }
    }

    @Test
    void serverMinutesPersistAcrossReopen() {
        UUID uuid = UUID.randomUUID();
        try (PlayerStore players = open()) {
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "world/Creative");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "world/Creative");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 2), "live:two", "mc.example.com:25566");
        }
        try (PlayerStore players = open()) {
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(3, snapshot.totalMinutes());
            assertEquals("world/Creative", snapshot.mostPlayedServer().orElseThrow().serverId());
            assertEquals(2, snapshot.mostPlayedServer().orElseThrow().minutes());
        }
    }

    @Test
    void sameSessionIdCountsOnceAcrossServers() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:boot", "hypixel.net");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:boot", "world/Survival");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(1, snapshot.sessionCount());
            assertEquals(2, snapshot.totalMinutes());
            assertEquals(2, players.sessionMinutes(uuid, "live:boot"));
        }
    }

    @Test
    void currentSessionMinutesDoNotCountAsPlayedBefore() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:now", "hypixel.net");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:now", "hypixel.net");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(2, players.sessionMinutes(uuid, "live:now"));
            assertFalse(snapshot.hasPlayedBefore(players.sessionMinutes(uuid, "live:now")));

            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 2), "live:earlier", "hypixel.net");
            for (int i = 0; i < 6; i++) {
                players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 2), "live:earlier", "hypixel.net");
            }
            PlayerSnapshot later = players.get(uuid).orElseThrow();
            assertEquals(2, players.sessionMinutes(uuid, "live:now"));
            assertTrue(later.hasPlayedBefore(players.sessionMinutes(uuid, "live:now")));
        }
    }

    @Test
    void playRequiresAServerId() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () ->
                players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", null));
            assertThrows(IllegalArgumentException.class, () ->
                players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", " "));
            assertTrue(players.get(uuid).isEmpty());
        }
    }

    @Test
    void persistsProfileLookups() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        Instant lastValid = Instant.parse("2026-08-01T00:00:00Z");
        try (PlayerStore players = open()) {
            players.profiles().put(new ProfileMapping(uuid, "Steve", lastValid));
            players.profiles().put(new ProfileMapping(null, "nobody", lastValid));
        }
        try (PlayerStore players = open()) {
            ProfileMapping steve = players.profiles().byName("steve").orElseThrow();
            assertEquals(uuid, steve.uuid());
            assertEquals("Steve", steve.username());
            assertEquals(lastValid, steve.lastValid());
            assertEquals("Steve", players.profiles().byUuid(uuid).orElseThrow().username());
            ProfileMapping nobody = players.profiles().byName("nobody").orElseThrow();
            assertNull(nobody.uuid());
            assertFalse(nobody.resolved());
        }
    }

    @Test
    void lastPlayedTogetherIgnoresToday() {
        LocalDate today = LocalDate.now();
        LocalDate earlier = today.minusDays(5);
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.recordLivePlay(uuid, "Steve", earlier, "live:one", "hypixel.net");
            players.recordLivePlay(uuid, "Steve", earlier, "live:one", "hypixel.net");
            players.recordLivePlay(uuid, "Steve", today, "live:two", "mc.example.com");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(earlier, snapshot.lastPlayedBeforeToday().orElseThrow());
            assertEquals("hypixel.net", snapshot.mostPlayedServer().orElseThrow().serverId());
        }
    }

    @Test
    void onlyTodayHasNoEarlierPlayDay() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.recordLivePlay(uuid, "Steve", LocalDate.now(), "live:one", "hypixel.net");
            assertTrue(players.get(uuid).orElseThrow().lastPlayedBeforeToday().isEmpty());
        }
    }

    @Test
    void noteTimestampSurvivesPlayUpdates() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.setNote(uuid, "Steve", "met on Discord");
            Instant taken = players.get(uuid).orElseThrow().noteTakenAt().orElseThrow();
            players.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals("met on Discord", snapshot.note().orElseThrow());
            assertEquals(taken, snapshot.noteTakenAt().orElseThrow());
            assertEquals("Alex", snapshot.currentUsername());
        }
    }

    @Test
    void blankNoteClearsStoredNote() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.setNote(uuid, "Steve", "hello");
            players.setNote(uuid, "Steve", " ");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertTrue(snapshot.note().isEmpty());
            assertTrue(snapshot.noteTakenAt().isEmpty());
        }
    }

    @Test
    void livePlayReportsPreviousSeenNameOnRename() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            assertTrue(players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").isEmpty());
            assertEquals("Steve", players.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").orElseThrow());
            assertTrue(players.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").isEmpty());
            assertTrue(players.recordLivePlay(uuid, "alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").isEmpty());
            assertEquals("Alex", players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 3), "live:three", "hypixel.net").orElseThrow());
            assertEquals("Steve", players.get(uuid).orElseThrow().currentUsername());
        }
    }

    @Test
    void notesAloneDoNotCountAsAPreviouslySeenName() {
        try (PlayerStore players = open()) {
            UUID uuid = UUID.randomUUID();
            players.setNote(uuid, "Ghost", "met them on Discord");
            assertTrue(players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").isEmpty());
        }
    }

    @Test
    void keepsOnlyTheLastThreeDaysAndSessions() {
        LocalDate today = LocalDate.now();
        UUID uuid = UUID.randomUUID();
        try (PlayerStore players = open()) {
            for (int i = 3; i >= 0; i--) {
                players.recordLivePlay(uuid, "Steve", today.minusDays(i), "live:" + i, "hypixel.net");
            }
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(4, snapshot.totalMinutes());
            assertEquals(4, snapshot.daysPlayed());
            assertEquals(4, snapshot.sessionCount());
            assertEquals(today.minusDays(1), snapshot.lastPlayedBeforeToday().orElseThrow());
            assertEquals(0, players.sessionMinutes(uuid, "live:3"));
            assertEquals(1, players.sessionMinutes(uuid, "live:0"));
        }
    }
}
