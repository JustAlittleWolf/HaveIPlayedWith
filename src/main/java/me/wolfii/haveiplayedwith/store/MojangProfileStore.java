package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class MojangProfileStore {
    private final StoreSession session;

    MojangProfileStore(StoreSession session) {
        this.session = session;
    }

    public Optional<MojangUuidCache> byUuid(UUID uuid) {
        return session.call(() -> {
            byte[] raw = session.mojangUuid.get(StoreKeys.uuid(uuid));
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.MojangUuidRow row = StoreCodec.mojangUuid(raw);
            return Optional.of(new MojangUuidCache(row.username(), Instant.ofEpochMilli(row.fetchedAt())));
        });
    }

    public void putUuid(UUID uuid, String username, Instant fetchedAt) {
        session.run(() -> session.mojangUuid.put(StoreKeys.uuid(uuid), StoreCodec.mojangUuid(new StoreRows.MojangUuidRow(
            username == null ? "" : username,
            fetchedAt.toEpochMilli()
        ))));
    }

    public Optional<MojangNameCache> byName(String usernameLower) {
        return session.call(() -> {
            byte[] raw = session.mojangName.get(usernameLower);
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.MojangNameRow row = StoreCodec.mojangName(raw);
            UUID uuid = row.uuid().isBlank() ? null : UUID.fromString(row.uuid());
            String username = row.username().isBlank() ? null : row.username();
            return Optional.of(new MojangNameCache(uuid, username, Instant.ofEpochMilli(row.fetchedAt())));
        });
    }

    public void putName(String usernameLower, MojangNameCache cache) {
        session.run(() -> session.mojangName.put(usernameLower, StoreCodec.mojangName(new StoreRows.MojangNameRow(
            cache.uuid() == null ? "" : cache.uuid().toString(),
            cache.username() == null ? "" : cache.username(),
            cache.fetchedAt().toEpochMilli()
        ))));
    }
}
