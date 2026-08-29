package me.wolfii.haveiplayedwith.store;

import org.h2.mvstore.MVStore;
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
    void discardsLegacyNitriteMaps() {
        Path file = temp.resolve("store.db");
        try (MVStore store = new MVStore.Builder().fileName(StoreMv.path(file)).open()) {
            store.openMap("$nitrite_catalog");
            store.openMap("username_history").put("dead", "row");
            store.commit();
        }
        assertTrue(StoreMv.size(file) > 0);
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        try (StoreDb db = StoreDb.open(file)) {
            db.run(() -> db.recordLivePlay(uuid, "Steve", LocalDate.of(2026, 8, 1), "live:one", "hypixel.net"));
        }
        try (StoreDb db = StoreDb.open(file)) {
            assertEquals(1, db.call(() -> db.snapshot(uuid).orElseThrow().totalMinutes()));
            assertEquals(1, db.call(() -> db.findByName("steve")).size());
        }
        try (MVStore store = new MVStore.Builder().fileName(StoreMv.path(file)).readOnly().open()) {
            assertFalse(store.hasMap("$nitrite_catalog"));
            assertFalse(store.hasMap("username_history"));
        }
    }
}