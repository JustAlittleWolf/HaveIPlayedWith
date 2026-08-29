package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreCompactTest {
    @TempDir
    Path temp;

    @Test
    void rewriteReclaimsDeadChunksAndKeepsRows() {
        Path file = temp.resolve("store.db");
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        LocalDate day = LocalDate.of(2026, 8, 1);
        List<UUID> ids = new ArrayList<>();
        ids.add(uuid);
        for (int i = 1; i < 1200; i++) {
            ids.add(UUID.randomUUID());
        }
        try (StoreDb db = StoreDb.open(file)) {
            for (int minute = 0; minute < 60; minute++) {
                db.run(() -> {
                    for (UUID id : ids) {
                        db.recordLivePlay(id, "Steve", day, "live:one", "hypixel.net");
                    }
                    db.flushPending();
                });
            }
            long bloated = StoreMv.size(file);
            assertTrue(db.call(db::hasReclaimableSpace), "file=" + bloated);
            db.run(db::compactIfWorthwhile);
            long compacted = StoreMv.size(file);
            assertTrue(compacted < bloated / 2, "bloated=" + bloated + " compacted=" + compacted);
            assertFalse(db.call(db::hasReclaimableSpace));
            PlayerSnapshot snapshot = db.call(() -> db.snapshot(uuid).orElseThrow());
            assertEquals(60, snapshot.totalMinutes());
            assertEquals(60L, db.call(() -> db.sessionMinutes(uuid, "live:one")));
            assertEquals("hypixel.net", snapshot.mostPlayedServer().orElseThrow().serverId());
        }
        try (StoreDb db = StoreDb.open(file)) {
            assertEquals(60, db.call(() -> db.snapshot(uuid).orElseThrow().totalMinutes()));
        }
    }

    @Test
    void repeatedMinuteTicksDoNotLeaveDeadChunks() {
        Path file = temp.resolve("store.db");
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        LocalDate day = LocalDate.of(2026, 8, 1);
        List<UUID> ids = new ArrayList<>();
        ids.add(uuid);
        for (int i = 1; i < 80; i++) {
            ids.add(UUID.randomUUID());
        }
        try (StoreDb db = StoreDb.open(file)) {
            for (int minute = 0; minute < 40; minute++) {
                db.run(() -> {
                    for (UUID id : ids) {
                        db.recordLivePlay(id, "Steve", day, "live:one", "hypixel.net");
                    }
                });
            }
            long size = StoreMv.size(file);
            assertFalse(db.call(db::hasReclaimableSpace), "file=" + size);
            PlayerSnapshot snapshot = db.call(() -> db.snapshot(uuid).orElseThrow());
            assertEquals(40, snapshot.totalMinutes());
            assertEquals(40L, db.call(() -> db.sessionMinutes(uuid, "live:one")));
            db.run(db::flushPending);
        }
        try (StoreDb db = StoreDb.open(file)) {
            assertEquals(40, db.call(() -> db.snapshot(uuid).orElseThrow().totalMinutes()));
            assertEquals(40L, db.call(() -> db.sessionMinutes(uuid, "live:one")));
        }
    }

    @Test
    void smallDenseStoreIsLeftAlone() {
        Path file = temp.resolve("store.db");
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> {
                db.recordLivePlay(UUID.randomUUID(), "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
                db.flushPending();
            });
            long size = StoreMv.size(file);
            assertFalse(db.call(db::hasReclaimableSpace));
            db.run(db::compactIfWorthwhile);
            assertEquals(size, StoreMv.size(file));
        }
    }

    @Test
    void compactedPlayLogStaysSmall() {
        Path file = temp.resolve("store.db");
        LocalDate day = LocalDate.of(2026, 8, 29);
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> {
                for (int i = 0; i < 790; i++) {
                    UUID id = new UUID(i, i + 1);
                    db.recordLivePlay(id, "Player" + (i % 1000), day, "live:one", "hypixel.net");
                    for (int minute = 1; minute < 18; minute++) {
                        db.recordLivePlay(id, "Player" + (i % 1000), day, "live:one", "hypixel.net");
                    }
                }
                db.flushPending();
            });
        }
        try (StoreDb db = StoreDb.open(file)) {
            db.run(db::compactIfWorthwhile);
            long size = StoreMv.size(file);
            assertTrue(size < 200_000, "compacted=" + size);
            assertEquals(18, db.call(() -> db.snapshot(new UUID(0, 1)).orElseThrow().totalMinutes()));
        }
    }
}