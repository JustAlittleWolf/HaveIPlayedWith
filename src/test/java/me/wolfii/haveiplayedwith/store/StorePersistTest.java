package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorePersistTest {
    @TempDir
    Path temp;

    @Test
    void minuteTicksSurviveReopen() {
        Path file = temp.resolve("store.db");
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        LocalDate day = LocalDate.of(2026, 8, 1);
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> {
                for (int minute = 0; minute < 18; minute++) {
                    db.recordLivePlay(uuid, "Steve", day, "live:one", "hypixel.net");
                }
            });
        }
        try (StoreDb db = StoreDb.open(file)) {
            PlayerSnapshot snapshot = db.call(() -> db.snapshot(uuid).orElseThrow());
            assertEquals(18, snapshot.totalMinutes());
            assertEquals(18L, db.call(() -> db.sessionMinutes(uuid, "live:one")));
            assertEquals("hypixel.net", snapshot.mostPlayedServer().orElseThrow().serverId());
        }
    }

    @Test
    void playLogStaysSmall() {
        Path file = temp.resolve("store.db");
        LocalDate day = LocalDate.of(2026, 8, 29);
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> {
                for (int i = 0; i < 790; i++) {
                    UUID id = new UUID(i, i + 1);
                    String name = "Player" + (i % 1000);
                    for (int minute = 0; minute < 18; minute++) {
                        db.recordLivePlay(id, name, day, "live:one", "hypixel.net");
                    }
                }
            });
        }
        long size = StoreMv.size(file);
        assertTrue(size < 200_000, "size=" + size);
        try (StoreDb db = StoreDb.open(file)) {
            assertEquals(18, db.call(() -> db.snapshot(new UUID(0, 1)).orElseThrow().totalMinutes()));
        }
    }

    @Test
    void queuedPlayIsVisibleAndFlushedOnClose() {
        Path file = temp.resolve("queued.db");
        UUID uuid = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 8, 1);
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> {
                for (int minute = 0; minute < 9; minute++) {
                    db.recordLivePlay(uuid, "Steve", day, "live:one", "hypixel.net");
                }
                db.recordLivePlay(uuid, "Alex", day, "live:one", "hypixel.net");
                assertEquals(1, db.pendingPlayerWrites());
                PlayerSnapshot snapshot = db.snapshot(uuid).orElseThrow();
                assertEquals(10, snapshot.totalMinutes());
                assertEquals("Alex", snapshot.currentUsername());
                assertFalse(db.playerRowStored(uuid));
            });
        }
        try (StoreDb db = StoreDb.open(file)) {
            PlayerSnapshot snapshot = db.call(() -> db.snapshot(uuid).orElseThrow());
            assertEquals(10, snapshot.totalMinutes());
            assertEquals("Alex", snapshot.currentUsername());
        }
    }

    @Test
    void pendingWritesStayWithinCap() {
        Path file = temp.resolve("capped.db");
        int total = WriteQueue.MAX_PENDING + 40;
        UUID oldest = new UUID(1, 3);
        UUID newest = new UUID(total, 3);
        LocalDate day = LocalDate.of(2026, 8, 1);
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> {
                for (int i = 1; i <= total; i++) {
                    db.recordLivePlay(new UUID(i, 3), "Steve", day, "live:one", "hypixel.net");
                    assertTrue(db.pendingPlayerWrites() <= WriteQueue.MAX_PENDING, "i=" + i);
                }
                assertEquals(WriteQueue.MAX_PENDING, db.pendingPlayerWrites());
                assertTrue(db.playerRowStored(oldest));
                assertFalse(db.playerRowStored(newest));
                assertEquals(1, db.snapshot(newest).orElseThrow().totalMinutes());
            });
        }
        try (StoreDb db = StoreDb.open(file)) {
            assertEquals(1, db.call(() -> db.snapshot(oldest).orElseThrow().totalMinutes()));
            assertEquals(1, db.call(() -> db.snapshot(newest).orElseThrow().totalMinutes()));
            assertEquals(1, db.call(() -> db.snapshot(new UUID(total / 2, 3)).orElseThrow().totalMinutes()));
        }
    }

    @Test
    void writeTurnsDrainTheQueue() throws Exception {
        Path file = temp.resolve("paced.db");
        UUID first = new UUID(1, 5);
        int count = 25;
        LocalDate day = LocalDate.of(2026, 8, 1);
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> {
                for (int i = 1; i <= count; i++) {
                    db.recordLivePlay(new UUID(i, 5), "Steve", day, "live:one", "hypixel.net");
                }
                assertEquals(count, db.pendingPlayerWrites());
                assertFalse(db.playerRowStored(first));
            });
            long deadline = System.nanoTime() + 5_000_000_000L;
            int pending = count;
            boolean stored = false;
            while (System.nanoTime() < deadline) {
                pending = db.call(db::pendingPlayerWrites);
                stored = db.call(() -> db.playerRowStored(first));
                if (pending < count && stored) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(stored, "oldest row was not written");
            assertTrue(pending < count, "pending=" + pending);
        }
    }
}
