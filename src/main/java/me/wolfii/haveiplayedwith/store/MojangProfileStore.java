package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class MojangProfileStore {
    private final StoreDb db;

    MojangProfileStore(StoreDb db) {
        this.db = db;
    }

    public Optional<MojangUuidCache> byUuid(UUID uuid) {
        return db.call(() -> db.mojangUuid(uuid));
    }

    public void putUuid(UUID uuid, String username, Instant fetchedAt) {
        db.run(() -> db.putMojangUuid(uuid, username, fetchedAt));
    }

    public Optional<MojangNameCache> byName(String usernameLower) {
        return db.call(() -> db.mojangName(usernameLower));
    }

    public void putName(String usernameLower, MojangNameCache cache) {
        db.run(() -> db.putMojangName(usernameLower, cache));
    }

    /** Latest current-name mapping, both directions, one commit. */
    public void putCurrent(UUID uuid, String username, Instant fetchedAt) {
        db.run(() -> db.putMojangCurrent(uuid, username, fetchedAt));
    }
}
