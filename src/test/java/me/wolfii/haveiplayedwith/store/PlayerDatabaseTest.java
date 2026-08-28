package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDatabaseTest {
    @TempDir
    Path temp;

    @Test
    void recordsPlayAndFindsPastNames() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
            database.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "world/Survival");
            database.setNote(uuid, "Alex", "builds nice farms");

            List<PlayerSnapshot> byOld = database.findByName("steve");
            assertEquals(1, byOld.size());
            PlayerSnapshot snapshot = byOld.getFirst();
            assertEquals("Alex", snapshot.currentUsername());
            assertEquals(3, snapshot.totalMinutes());
            assertEquals(2, snapshot.daysPlayed());
            assertEquals(2, snapshot.sessionCount());
            assertEquals("builds nice farms", snapshot.note().orElseThrow());
            assertTrue(snapshot.noteTakenAt().isPresent());
            assertEquals(LocalDate.of(2026, 8, 2), snapshot.lastPlayedBeforeToday().orElseThrow());
            assertEquals("hypixel.net", snapshot.mostPlayedServer().orElseThrow().serverId());
            assertTrue(snapshot.pastNames().stream().anyMatch(name -> name.username().equals("Steve")));
            assertEquals(List.of(
                new ServerPlay("hypixel.net", 2),
                new ServerPlay("world/Survival", 1)
            ), snapshot.servers());
        }
    }

    @Test
    void importDoesNotAddMinutes() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
            PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
            assertEquals(0, snapshot.totalMinutes());
            assertEquals(1, snapshot.daysPlayed());
            assertEquals("Current", snapshot.currentUsername());
            assertEquals("OldName", snapshot.pastNames().getFirst().username());
            assertTrue(snapshot.servers().isEmpty());
        }
    }

    @Test
    void importOnlyRecordsTheNameThatWasSeen() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
            List<String> seen = database.get(uuid).orElseThrow().names().stream().map(SeenName::username).toList();
            assertEquals(List.of("OldName"), seen);
        }
    }

    @Test
    void notesDoNotCountAsHavingPlayedTogether() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.setNote(uuid, "Ghost", "met them on Discord");
            PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
            assertEquals(0, snapshot.totalMinutes());
            assertEquals(0, snapshot.daysPlayed());
            assertEquals(0, snapshot.sessionCount());
            assertTrue(snapshot.names().isEmpty());
            assertTrue(snapshot.note().isPresent());
            assertTrue(snapshot.noteTakenAt().isPresent());
            assertFalse(snapshot.hasPlayed());
            assertEquals(1, database.findByName("ghost").size());
            assertTrue(snapshot.servers().isEmpty());
        }
    }

    @Test
    void serverMinutesPersistAcrossReopen() {
        UUID uuid = UUID.randomUUID();
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "world/Creative");
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "world/Creative");
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 2), "live:two", "mc.example.com:25566");
        }
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
            assertEquals(3, snapshot.totalMinutes());
            assertEquals(List.of(
                new ServerPlay("world/Creative", 2),
                new ServerPlay("mc.example.com:25566", 1)
            ), snapshot.servers());
        }
    }

    @Test
    void sameSessionIdCountsOnceAcrossServers() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:boot", "hypixel.net");
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:boot", "world/Survival");
            PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
            assertEquals(1, snapshot.sessionCount());
            assertEquals(2, snapshot.totalMinutes());
        }
    }

    @Test
    void blankServerIdDoesNotCreateAServerRow() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", null);
            database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", " ");
            PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
            assertEquals(2, snapshot.totalMinutes());
            assertTrue(snapshot.servers().isEmpty());
        }
    }

    @Test
    void persistsMojangNameLookups() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            database.putMojangNameCache("steve", new MojangNameCache(uuid, "Steve", Instant.parse("2026-08-01T00:00:00Z")));
            database.putMojangNameCache("nobody", new MojangNameCache(null, "", Instant.parse("2026-08-01T00:00:00Z")));
        }
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            MojangNameCache steve = database.mojangNameCache("steve").orElseThrow();
            assertEquals(UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"), steve.uuid());
            assertEquals("Steve", steve.username());
            MojangNameCache nobody = database.mojangNameCache("nobody").orElseThrow();
            assertNull(nobody.uuid());
            assertNull(nobody.username());
        }
    }

    @Test
    void lastPlayedTogetherIgnoresToday() {
        LocalDate today = LocalDate.now();
        LocalDate earlier = today.minusDays(5);
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.recordLivePlay(uuid, "Steve", earlier, "live:one", "hypixel.net");
            database.recordLivePlay(uuid, "Steve", earlier, "live:one", "hypixel.net");
            database.recordLivePlay(uuid, "Steve", today, "live:two", "mc.example.com");
            PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
            assertEquals(earlier, snapshot.lastPlayedBeforeToday().orElseThrow());
            assertEquals("hypixel.net", snapshot.mostPlayedServer().orElseThrow().serverId());
        }
    }

    @Test
    void onlyTodayHasNoEarlierPlayDay() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.recordLivePlay(uuid, "Steve", LocalDate.now(), "live:one", "hypixel.net");
            assertTrue(database.get(uuid).orElseThrow().lastPlayedBeforeToday().isEmpty());
        }
    }

    @Test
    void noteTimestampSurvivesPlayUpdates() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.setNote(uuid, "Steve", "met on Discord");
            Instant taken = database.get(uuid).orElseThrow().noteTakenAt().orElseThrow();
            database.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
            PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
            assertEquals("met on Discord", snapshot.note().orElseThrow());
            assertEquals(taken, snapshot.noteTakenAt().orElseThrow());
            assertEquals("Alex", snapshot.currentUsername());
        }
    }

    @Test
    void storedPlayerRowsDoNotUseNulls() {
        StoreRows.PlayerRow row = new StoreRows.PlayerRow("Alex", null, 0, 1, 1);
        assertEquals("Alex", row.currentUsername());
        assertEquals("", row.note());
        assertEquals(0L, row.noteTakenAt());
        String json = new com.google.gson.Gson().toJson(row);
        assertFalse(json.contains("null"));
        StoreRows.PlayerRow parsed = new com.google.gson.Gson().fromJson(json, StoreRows.PlayerRow.class);
        assertEquals("", parsed.note());
        assertEquals(0L, parsed.noteTakenAt());
    }

    @Test
    void livePlayReportsPreviousSeenNameOnRename() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            assertTrue(database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").isEmpty());
            assertEquals("Steve", database.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").orElseThrow());
            assertTrue(database.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").isEmpty());
            assertTrue(database.recordLivePlay(uuid, "alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").isEmpty());
        }
    }

    @Test
    void importedPlayersAnnounceWhenFirstSeenLiveUnderANewName() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
            assertEquals("OldName", database.recordLivePlay(uuid, "Current", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").orElseThrow());
        }
    }

    @Test
    void notesAloneDoNotCountAsAPreviouslySeenName() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            database.setNote(uuid, "Ghost", "met them on Discord");
            assertTrue(database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").isEmpty());
        }
    }

    @Test
    void importProgressKeepsSilenceAcrossReopen() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            database.saveImportProgress(new ImportProgress(
                ImportProgress.SOURCE_ALLTHELOGS, 12, 100, null, 0, ImportProgress.STATUS_RUNNING, true
            ));
        }
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            ImportProgress progress = database.importProgress(ImportProgress.SOURCE_ALLTHELOGS).orElseThrow();
            assertEquals(12, progress.processed());
            assertTrue(progress.silenced());
            assertEquals(ImportProgress.STATUS_RUNNING, progress.status());
        }
    }
}
