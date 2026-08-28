package me.wolfii.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDatabaseTest {
	@TempDir
	Path temp;

	@Test
	void recordsPlayAndFindsPastNames() {
		try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.sqlite"))) {
			UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
			database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one");
			database.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one");
			database.recordLivePlay(uuid, "Alex", LocalDate.of(2026, 8, 2), "live:two");
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
		}
	}

	@Test
	void importDoesNotAddMinutes() {
		try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.sqlite"))) {
			UUID uuid = UUID.randomUUID();
			database.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
			PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
			assertEquals(0, snapshot.totalMinutes());
			assertEquals(1, snapshot.daysPlayed());
			assertEquals("Current", snapshot.currentUsername());
			assertEquals("OldName", snapshot.pastNames().getFirst().username());
		}
	}

	@Test
	void importOnlyRecordsTheNameThatWasSeen() {
		try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.sqlite"))) {
			UUID uuid = UUID.randomUUID();
			database.recordImportedSighting(uuid, "OldName", "Current", LocalDate.of(2026, 1, 1), "atl:file:a", Instant.parse("2026-01-01T12:00:00Z"));
			List<String> seen = database.get(uuid).orElseThrow().names().stream().map(SeenName::username).toList();
			assertEquals(List.of("OldName"), seen);
		}
	}

	@Test
	void notesDoNotCountAsHavingPlayedTogether() {
		try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.sqlite"))) {
			UUID uuid = UUID.randomUUID();
			database.setNote(uuid, "Ghost", "met them on Discord");
			PlayerSnapshot snapshot = database.get(uuid).orElseThrow();
			assertEquals(0, snapshot.totalMinutes());
			assertEquals(0, snapshot.daysPlayed());
			assertEquals(0, snapshot.sessionCount());
			assertTrue(snapshot.names().isEmpty());
		}
	}
}
