package me.wolfii.haveiplayedwith.store;

import java.util.Optional;
import java.util.UUID;

public final class ProfileCache {
    private final StoreDb db;

    ProfileCache(StoreDb db) {
        this.db = db;
    }

    public Optional<ProfileMapping> byUuid(UUID uuid) {
        return db.call(() -> db.profileByUuid(uuid));
    }

    public Optional<ProfileMapping> byName(String usernameLower) {
        return db.call(() -> db.profileByName(usernameLower));
    }

    public void put(ProfileMapping mapping) {
        db.run(() -> db.putProfile(mapping));
    }
}
