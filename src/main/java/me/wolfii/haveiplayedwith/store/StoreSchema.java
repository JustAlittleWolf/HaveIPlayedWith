package me.wolfii.haveiplayedwith.store;

import org.dizitart.no2.Nitrite;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.index.IndexOptions;
import org.dizitart.no2.index.IndexType;

/**
 * Collection names and indexes, created once when the database is opened.
 * Documents use {@link #KEY} as the unique row id. Missing strings are stored
 * as empty strings, which is what {@link StoreDb} reads back.
 */
final class StoreSchema {
    static final String KEY = "_key";
    static final String PLAYERS = "players";
    static final String USERNAME_HISTORY = "username_history";
    static final String NAME_INDEX = "name_index";
    static final String PLAY_DAYS = "play_days";
    static final String PLAY_SESSIONS = "play_sessions";
    static final String PLAY_SERVERS = "play_servers";
    static final String MOJANG_UUID = "mojang_uuid";
    static final String MOJANG_NAME = "mojang_name";

    static final String PLAYER_UUID = "player_uuid";
    static final String CURRENT_USERNAME = "current_username";
    static final String NOTE = "note";
    static final String NOTE_TAKEN_AT = "note_taken_at";
    static final String TOTAL_MINUTES = "total_minutes";
    static final String SESSION_COUNT = "session_count";
    static final String USERNAME_LOWER = "username_lower";
    static final String USERNAME = "username";
    static final String LAST_SEEN = "last_seen";
    static final String PLAY_DAY = "play_day";
    static final String MINUTES = "minutes";
    static final String SESSION_ID = "session_id";
    static final String SERVER_ID = "server_id";
    static final String FETCHED_AT = "fetched_at";

    private StoreSchema() {
    }

    /** Adds any collection and index the database does not have yet. */
    static void create(Nitrite nitrite) {
        unique(nitrite.getCollection(PLAYERS), KEY);
        unique(nitrite.getCollection(USERNAME_HISTORY), KEY);
        nonUnique(nitrite.getCollection(USERNAME_HISTORY), PLAYER_UUID);
        unique(nitrite.getCollection(NAME_INDEX), KEY);
        nonUnique(nitrite.getCollection(NAME_INDEX), USERNAME_LOWER);
        unique(nitrite.getCollection(PLAY_DAYS), KEY);
        nonUnique(nitrite.getCollection(PLAY_DAYS), PLAYER_UUID);
        unique(nitrite.getCollection(PLAY_SESSIONS), KEY);
        unique(nitrite.getCollection(PLAY_SERVERS), KEY);
        nonUnique(nitrite.getCollection(PLAY_SERVERS), PLAYER_UUID);
        unique(nitrite.getCollection(MOJANG_UUID), KEY);
        unique(nitrite.getCollection(MOJANG_NAME), KEY);
    }

    private static void unique(NitriteCollection collection, String field) {
        if (!collection.hasIndex(field)) {
            collection.createIndex(IndexOptions.indexOptions(IndexType.UNIQUE), field);
        }
    }

    private static void nonUnique(NitriteCollection collection, String field) {
        if (!collection.hasIndex(field)) {
            collection.createIndex(IndexOptions.indexOptions(IndexType.NON_UNIQUE), field);
        }
    }
}
