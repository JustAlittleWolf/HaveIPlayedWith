package me.wolfii.haveiplayedwith.store;

import com.google.gson.Gson;
import org.h2.mvstore.MVStore;
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

    @Test
    void recordsPlayAndFindsPastNames() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
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
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            players.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(0, snapshot.totalMinutes());
            assertEquals(1, snapshot.daysPlayed());
            assertEquals("Current", snapshot.currentUsername());
            assertEquals("OldName", snapshot.pastNames().getFirst().username());
            assertTrue(snapshot.servers().isEmpty());
        }
    }

    @Test
    void importOnlyRecordsTheNameThatWasSeen() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            players.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
            List<String> seen = players.get(uuid).orElseThrow().names().stream().map(SeenName::username).toList();
            assertEquals(List.of("OldName"), seen);
        }
    }

    @Test
    void notesDoNotCountAsHavingPlayedTogether() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
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
            assertTrue(snapshot.servers().isEmpty());
        }
    }

    @Test
    void serverMinutesPersistAcrossReopen() {
        UUID uuid = UUID.randomUUID();
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "world/Creative");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "world/Creative");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 2), "live:two", "mc.example.com:25566");
        }
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(3, snapshot.totalMinutes());
            assertEquals(List.of(
                new ServerPlay("world/Creative", 2),
                new ServerPlay("mc.example.com:25566", 1)
            ), snapshot.servers());
        }
    }

    @Test
    void sameSessionIdCountsOnceAcrossServers() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:boot", "hypixel.net");
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:boot", "world/Survival");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(1, snapshot.sessionCount());
            assertEquals(2, snapshot.totalMinutes());
        }
    }

    @Test
    void blankServerIdDoesNotCreateAServerRow() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", null);
            players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", " ");
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals(2, snapshot.totalMinutes());
            assertTrue(snapshot.servers().isEmpty());
        }
    }

    @Test
    void persistsMojangNameLookups() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            players.mojangProfiles().putName("steve", new MojangNameCache(uuid, "Steve", Instant.parse("2026-08-01T00:00:00Z")));
            players.mojangProfiles().putName("nobody", new MojangNameCache(null, "", Instant.parse("2026-08-01T00:00:00Z")));
        }
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            MojangNameCache steve = players.mojangProfiles().byName("steve").orElseThrow();
            assertEquals(UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"), steve.uuid());
            assertEquals("Steve", steve.username());
            MojangNameCache nobody = players.mojangProfiles().byName("nobody").orElseThrow();
            assertNull(nobody.uuid());
            assertNull(nobody.username());
        }
    }

    @Test
    void lastPlayedTogetherIgnoresToday() {
        LocalDate today = LocalDate.now();
        LocalDate earlier = today.minusDays(5);
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
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
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            players.recordLivePlay(uuid, "Steve", LocalDate.now(), "live:one", "hypixel.net");
            assertTrue(players.get(uuid).orElseThrow().lastPlayedBeforeToday().isEmpty());
        }
    }

    @Test
    void noteTimestampSurvivesPlayUpdates() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
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
    void storedPlayerRowsDoNotUseNulls() {
        StoreRows.PlayerRow row = new StoreRows.PlayerRow("Alex", null, 0, 1, 1);
        assertEquals("Alex", row.currentUsername());
        assertEquals("", row.note());
        assertEquals(0L, row.noteTakenAt());
        StoreRows.PlayerRow decoded = StoreCodec.player(StoreCodec.player(row));
        assertEquals("", decoded.note());
        assertEquals(0L, decoded.noteTakenAt());
    }

    @Test
    void binaryPlayerRowIsSmallerThanJson() {
        StoreRows.PlayerRow row = new StoreRows.PlayerRow("Steve", "", 0, 3, 1);
        String json = new com.google.gson.Gson().toJson(row);
        byte[] binary = StoreCodec.player(row);
        assertTrue(binary.length < json.length(), () -> "binary " + binary.length + " vs json " + json.length());
    }

    @Test
    void migratesJsonMapsFromSchema1() {
        Path file = temp.resolve("players.mv");
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        writeSchema1Store(file, uuid);

        try (PlayerStore players = new PlayerStore(file)) {
            PlayerSnapshot snapshot = players.get(uuid).orElseThrow();
            assertEquals("Alex", snapshot.currentUsername());
            assertEquals(3, snapshot.totalMinutes());
            assertEquals(2, snapshot.daysPlayed());
            assertEquals(2, snapshot.sessionCount());
            assertEquals("builds nice farms", snapshot.note().orElseThrow());
            assertEquals(1, players.findByName("steve").size());
            assertEquals(1, players.findByName("alex").size());
            assertEquals(List.of(
                new ServerPlay("hypixel.net", 2),
                new ServerPlay("world/Survival", 1)
            ), snapshot.servers());
            ImportProgress progress = players.importProgress().get(ImportProgress.SOURCE_ALLTHELOGS).orElseThrow();
            assertEquals(12, progress.processed());
            assertTrue(progress.silenced());
        }

        try (PlayerStore players = new PlayerStore(file)) {
            assertEquals("Alex", players.get(uuid).orElseThrow().currentUsername());
            assertEquals(1, players.findByName("steve").size());
        }
    }

    @Test
    void livePlayReportsPreviousSeenNameOnRename() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            assertTrue(players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").isEmpty());
            assertEquals("Steve", players.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").orElseThrow());
            assertTrue(players.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").isEmpty());
            assertTrue(players.recordLivePlay(uuid, "alex", LocalDate.of(2026, 8, 2), "live:two", "hypixel.net").isEmpty());
        }
    }

    @Test
    void importedPlayersAnnounceWhenFirstSeenLiveUnderANewName() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            players.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
            assertEquals("OldName", players.recordLivePlay(uuid, "Current", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").orElseThrow());
        }
    }

    @Test
    void notesAloneDoNotCountAsAPreviouslySeenName() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            UUID uuid = UUID.randomUUID();
            players.setNote(uuid, "Ghost", "met them on Discord");
            assertTrue(players.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net").isEmpty());
        }
    }

    @Test
    void importProgressKeepsSilenceAcrossReopen() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            players.importProgress().save(new ImportProgress(
                ImportProgress.SOURCE_ALLTHELOGS, 12, 100, null, 0, ImportProgress.STATUS_RUNNING, true
            ));
        }
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            ImportProgress progress = players.importProgress().get(ImportProgress.SOURCE_ALLTHELOGS).orElseThrow();
            assertEquals(12, progress.processed());
            assertTrue(progress.silenced());
            assertEquals(ImportProgress.STATUS_RUNNING, progress.status());
        }
    }

    /**
     * Writes a schema-1 file the way HaveIPlayedWith 1.0 did: default ObjectDataType maps
     * whose values are Gson JSON (and decimal strings for counters).
     */
    private static void writeSchema1Store(Path file, UUID uuid) {
        Gson gson = new Gson();
        String id = uuid.toString();
        try (MVStore store = new MVStore.Builder().fileName(file.toAbsolutePath().toString()).compress().open()) {
            store.openMap("players").put(id, gson.toJson(new StoreRows.PlayerRow("Alex", "builds nice farms", 1L, 3, 2)));
            store.openMap("username_history").put(id + "\tsteve", gson.toJson(new StoreRows.HistoryRow("Steve", Instant.parse("2026-08-01T00:00:00Z").toEpochMilli())));
            store.openMap("username_history").put(id + "\talex", gson.toJson(new StoreRows.HistoryRow("Alex", Instant.parse("2026-08-02T00:00:00Z").toEpochMilli())));
            store.openMap("name_index").put("steve", gson.toJson(new String[]{id}));
            store.openMap("name_index").put("alex", gson.toJson(new String[]{id}));
            store.openMap("play_days").put(id + "\t2026-08-01", "2");
            store.openMap("play_days").put(id + "\t2026-08-02", "1");
            store.openMap("play_sessions").put(id + "\tlive:one", "1");
            store.openMap("play_sessions").put(id + "\tlive:two", "1");
            store.openMap("play_servers").put(id + "\thypixel.net", "2");
            store.openMap("play_servers").put(id + "\tworld/Survival", "1");
            store.openMap("import_progress").put(
                ImportProgress.SOURCE_ALLTHELOGS,
                gson.toJson(new StoreRows.ImportRow(12, 100, "", 0, ImportProgress.STATUS_RUNNING, true))
            );
            store.commit();
        }
    }
}
