package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.util.Optional;

public final class CraftyProfileStore {
    private final StoreSession session;

    CraftyProfileStore(StoreSession session) {
        this.session = session;
    }

    public Optional<CraftyCache> get(String usernameLower) {
        return session.call(() -> {
            byte[] raw = session.crafty.get(usernameLower);
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.CraftyRow row = StoreCodec.crafty(raw);
            return Optional.of(new CraftyCache(
                row.uuid(),
                row.currentUsername(),
                row.usernamesJson(),
                row.valid(),
                Instant.ofEpochMilli(row.fetchedAt())
            ));
        });
    }

    public void put(String usernameLower, CraftyCache cache) {
        session.run(() -> session.crafty.put(usernameLower, StoreCodec.crafty(new StoreRows.CraftyRow(
            cache.uuid() == null ? "" : cache.uuid(),
            cache.currentUsername() == null ? "" : cache.currentUsername(),
            cache.usernamesJson() == null || cache.usernamesJson().isBlank() ? "[]" : cache.usernamesJson(),
            cache.valid(),
            cache.fetchedAt().toEpochMilli()
        ))));
    }
}
