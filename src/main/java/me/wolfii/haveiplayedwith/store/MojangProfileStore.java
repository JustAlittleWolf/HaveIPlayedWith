package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class MojangProfileStore {
    private final StoreDb db;

    MojangProfileStore(StoreDb db) {
        this.db = db;
    }

    public Optional<MojangMapping> byUuid(UUID uuid) {
        return db.call(() -> db.mojangByUuid(uuid));
    }

    public Optional<MojangMapping> byName(String usernameLower) {
        return db.call(() -> db.mojangByName(usernameLower));
    }

    public void put(MojangMapping mapping) {
        db.run(() -> db.putMojang(mapping));
    }

    public void putUuid(UUID uuid, String username, Instant lastValid) {
        put(new MojangMapping(uuid, username, lastValid));
    }

    public void putName(String usernameLower, MojangMapping mapping) {
        if (mapping.uuid() != null) {
            put(mapping);
            return;
        }
        put(new MojangMapping(null, usernameLower, mapping.lastValid()));
    }

    /** Latest current-name mapping, both directions, one commit. */
    public void putCurrent(UUID uuid, String username, Instant lastValid) {
        put(new MojangMapping(uuid, username, lastValid));
    }
}
