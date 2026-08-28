package me.wolfii.haveiplayedwith.store;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.ByteArrayDataType;
import org.h2.mvstore.type.LongDataType;
import org.h2.mvstore.type.StringDataType;

/**
 * Typed MVStore maps. Keys stay human-readable strings so prefix scans keep working;
 * values are binary rows or packed longs, not JSON.
 */
final class StoreMaps {
    static final int SCHEMA = 2;
    static final String META = "hipw_meta";
    static final String SCHEMA_KEY = "schema";

    static final String PLAYERS = "players";
    static final String HISTORY = "username_history";
    static final String NAME_INDEX = "name_index";
    static final String PLAY_DAYS = "play_days";
    static final String PLAY_SESSIONS = "play_sessions";
    static final String PLAY_SERVERS = "play_servers";
    static final String MOJANG_UUID = "mojang_uuid";
    static final String MOJANG_NAME = "mojang_name";
    static final String CRAFTY = "crafty";
    static final String IMPORTS = "import_progress";

    private StoreMaps() {
    }

    static MVMap<String, byte[]> bytes(MVStore store, String name) {
        return store.openMap(name, new MVMap.Builder<String, byte[]>()
            .keyType(StringDataType.INSTANCE)
            .valueType(ByteArrayDataType.INSTANCE));
    }

    static MVMap<String, Long> longs(MVStore store, String name) {
        return store.openMap(name, new MVMap.Builder<String, Long>()
            .keyType(StringDataType.INSTANCE)
            .valueType(LongDataType.INSTANCE));
    }

    static MVMap<String, String> strings(MVStore store, String name) {
        return store.openMap(name, new MVMap.Builder<String, String>()
            .keyType(StringDataType.INSTANCE)
            .valueType(StringDataType.INSTANCE));
    }
}
