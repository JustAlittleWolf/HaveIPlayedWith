package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class MojangProfileStore {
    private final StoreSession session;

    MojangProfileStore(StoreSession session) {
        this.session = session;
    }

    public Optional<MojangUuidCache> byUuid(UUID uuid) {
        return session.call(() -> {
            String raw = session.mojangUuid.get(StoreKeys.uuid(uuid));
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.MojangUuidRow row = session.gson().fromJson(raw, StoreRows.MojangUuidRow.class);
            return Optional.of(new MojangUuidCache(row.username(), Instant.ofEpochMilli(row.fetchedAt())));
        });
    }

    public void putUuid(UUID uuid, String username, Instant fetchedAt) {
        session.run(() -> session.mojangUuid.put(StoreKeys.uuid(uuid), session.gson().toJson(new StoreRows.MojangUuidRow(
            username == null ? "" : username,
            fetchedAt.toEpochMilli()
        ))));
    }

    public Optional<MojangNameCache> byName(String usernameLower) {
        return session.call(() -> {
            String raw = session.mojangName.get(usernameLower);
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.MojangNameRow row = session.gson().fromJson(raw, StoreRows.MojangNameRow.class);
            UUID uuid = row.uuid().isBlank() ? null : UUID.fromString(row.uuid());
            String username = row.username().isBlank() ? null : row.username();
            return Optional.of(new MojangNameCache(uuid, username, Instant.ofEpochMilli(row.fetchedAt())));
        });
    }

    public void putName(String usernameLower, MojangNameCache cache) {
        session.run(() -> session.mojangName.put(usernameLower, session.gson().toJson(new StoreRows.MojangNameRow(
            cache.uuid() == null ? "" : cache.uuid().toString(),
            cache.username() == null ? "" : cache.username(),
            cache.fetchedAt().toEpochMilli()
        ))));
    }

    /** Latest current-name mapping, both directions, one commit. */
    public void putCurrent(UUID uuid, String username, Instant fetchedAt) {
        session.run(() -> {
            session.mojangUuid.put(StoreKeys.uuid(uuid), session.gson().toJson(new StoreRows.MojangUuidRow(
                username,
                fetchedAt.toEpochMilli()
            )));
            session.mojangName.put(username.toLowerCase(Locale.ROOT), session.gson().toJson(new StoreRows.MojangNameRow(
                uuid.toString(),
                username,
                fetchedAt.toEpochMilli()
            )));
        });
    }
}
