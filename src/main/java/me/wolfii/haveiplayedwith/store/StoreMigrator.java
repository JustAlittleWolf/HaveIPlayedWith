package me.wolfii.haveiplayedwith.store;

import com.google.gson.Gson;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Converts schema-1 stores (ObjectDataType maps holding Gson JSON) to schema 2
 * (typed binary / long maps). Runs once on open, then a single commit.
 */
final class StoreMigrator {
    private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
    private static final Gson GSON = new Gson();
    private static final String TMP = "__bin";

    private StoreMigrator() {
    }

    static void migrateIfNeeded(MVStore store) {
        if (schema(store) >= StoreMaps.SCHEMA) {
            return;
        }
        if (!store.hasMap(StoreMaps.PLAYERS)) {
            return;
        }
        LOGGER.info("Migrating HaveIPlayedWith database from JSON maps to binary rows");
        migrateBytes(store, StoreMaps.PLAYERS, raw -> StoreCodec.player(normalize(GSON.fromJson(raw, StoreRows.PlayerRow.class))));
        migrateBytes(store, StoreMaps.HISTORY, raw -> {
            StoreRows.HistoryRow row = GSON.fromJson(raw, StoreRows.HistoryRow.class);
            return StoreCodec.history(new StoreRows.HistoryRow(row.username(), row.lastSeen()));
        });
        migrateNameIndex(store);
        migrateLongs(store, StoreMaps.PLAY_DAYS);
        migrateLongs(store, StoreMaps.PLAY_SESSIONS);
        migrateLongs(store, StoreMaps.PLAY_SERVERS);
        migrateBytes(store, StoreMaps.MOJANG_UUID, raw -> StoreCodec.mojangUuid(normalize(GSON.fromJson(raw, StoreRows.MojangUuidRow.class))));
        migrateBytes(store, StoreMaps.MOJANG_NAME, raw -> StoreCodec.mojangName(normalize(GSON.fromJson(raw, StoreRows.MojangNameRow.class))));
        migrateBytes(store, StoreMaps.CRAFTY, raw -> StoreCodec.crafty(normalize(GSON.fromJson(raw, StoreRows.CraftyRow.class))));
        migrateBytes(store, StoreMaps.IMPORTS, raw -> StoreCodec.imports(normalize(GSON.fromJson(raw, StoreRows.ImportRow.class))));
        StoreMaps.strings(store, StoreMaps.META).put(StoreMaps.SCHEMA_KEY, Integer.toString(StoreMaps.SCHEMA));
        store.commit();
    }

    private static int schema(MVStore store) {
        if (!store.hasMap(StoreMaps.META)) {
            return 0;
        }
        String raw = StoreMaps.strings(store, StoreMaps.META).get(StoreMaps.SCHEMA_KEY);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        return Integer.parseInt(raw);
    }

    private static void migrateBytes(MVStore store, String name, Converter converter) {
        if (!store.hasMap(name)) {
            return;
        }
        dropTmp(store, name);
        MVMap<String, Object> old = store.openMap(name);
        MVMap<String, byte[]> neu = StoreMaps.bytes(store, name + TMP);
        for (var entry : old.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            neu.put(entry.getKey(), converter.convert(String.valueOf(value)));
        }
        store.removeMap(old);
        store.renameMap(neu, name);
    }

    private static void migrateLongs(MVStore store, String name) {
        if (!store.hasMap(name)) {
            return;
        }
        dropTmp(store, name);
        MVMap<String, Object> old = store.openMap(name);
        MVMap<String, Long> neu = StoreMaps.longs(store, name + TMP);
        for (var entry : old.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            neu.put(entry.getKey(), Long.parseLong(String.valueOf(value)));
        }
        store.removeMap(old);
        store.renameMap(neu, name);
    }

    private static void migrateNameIndex(MVStore store) {
        if (!store.hasMap(StoreMaps.NAME_INDEX)) {
            return;
        }
        dropTmp(store, StoreMaps.NAME_INDEX);
        MVMap<String, Object> old = store.openMap(StoreMaps.NAME_INDEX);
        MVMap<String, Long> neu = StoreMaps.longs(store, StoreMaps.NAME_INDEX + TMP);
        for (var entry : old.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String[] ids = GSON.fromJson(String.valueOf(value), String[].class);
            if (ids == null) {
                continue;
            }
            for (String id : ids) {
                neu.put(StoreKeys.nameIndex(entry.getKey(), UUID.fromString(id)), 1L);
            }
        }
        store.removeMap(old);
        store.renameMap(neu, StoreMaps.NAME_INDEX);
    }

    private static void dropTmp(MVStore store, String name) {
        String tmp = name + TMP;
        if (store.hasMap(tmp)) {
            store.removeMap(store.openMap(tmp));
        }
    }

    private static StoreRows.PlayerRow normalize(StoreRows.PlayerRow row) {
        return new StoreRows.PlayerRow(row.currentUsername(), row.note(), row.noteTakenAt(), row.totalMinutes(), row.sessionCount());
    }

    private static StoreRows.MojangUuidRow normalize(StoreRows.MojangUuidRow row) {
        return new StoreRows.MojangUuidRow(row.username(), row.fetchedAt());
    }

    private static StoreRows.MojangNameRow normalize(StoreRows.MojangNameRow row) {
        return new StoreRows.MojangNameRow(row.uuid(), row.username(), row.fetchedAt());
    }

    private static StoreRows.CraftyRow normalize(StoreRows.CraftyRow row) {
        return new StoreRows.CraftyRow(row.uuid(), row.currentUsername(), row.usernamesJson(), row.valid(), row.fetchedAt());
    }

    private static StoreRows.ImportRow normalize(StoreRows.ImportRow row) {
        return new StoreRows.ImportRow(row.processed(), row.total(), row.lastTimestamp(), row.skip(), row.status(), row.silenced());
    }

    @FunctionalInterface
    private interface Converter {
        byte[] convert(String json);
    }
}
