package me.wolfii.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			database.putMojangNameCache("steve", new PlayerDatabase.MojangNameCache(uuid, "Steve", Instant.parse("2026-08-01T00:00:00Z")));
			database.putMojangNameCache("nobody", new PlayerDatabase.MojangNameCache(null, null, Instant.parse("2026-08-01T00:00:00Z")));
		}
		try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
			PlayerDatabase.MojangNameCache steve = database.mojangNameCache("steve").orElseThrow();
			assertEquals(UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"), steve.uuid());
			assertEquals("Steve", steve.username());
			assertNull(database.mojangNameCache("nobody").orElseThrow().uuid());
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
