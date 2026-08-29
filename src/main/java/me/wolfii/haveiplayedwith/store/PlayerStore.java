package me.wolfii.haveiplayedwith.store;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player store backed by a compact MVStore file. Reads and writes run on the
 * database thread. Each change is written to the map immediately; H2 auto-commit
 * flushes and auto-compacts in the background.
 */
public final class PlayerStore implements AutoCloseable {
    private final StoreDb db;
    private final ProfileCache profiles;

    public PlayerStore(Path file) {
        this.db = StoreDb.open(file);
        this.profiles = new ProfileCache(db);
    }

    public ProfileCache profiles() {
        return profiles;
    }

    public List<PlayerSnapshot> findByName(String name) {
        return db.call(() -> db.findByName(name));
    }

    public Optional<PlayerSnapshot> get(UUID uuid) {
        return db.call(() -> db.snapshot(uuid));
    }

    public long sessionMinutes(UUID uuid, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        return db.call(() -> {
            Long minutes = db.sessionMinutes(uuid, sessionId);
            return minutes == null ? 0L : minutes;
        });
    }

    public void setNote(UUID uuid, String username, String note) {
        db.run(() -> {
            db.ensurePlayer(uuid, username);
            String cleaned = note == null || note.isBlank() ? "" : note;
            db.setNote(uuid, cleaned, cleaned.isEmpty() ? 0L : Instant.now().toEpochMilli());
        });
    }

    /**
     * @return the last name this player was seen as, when the new username is different
     */
    public Optional<String> recordLivePlay(UUID uuid, String username, LocalDate day, String sessionId, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId");
        }
        return db.call(() -> db.recordLivePlay(uuid, username, day, sessionId, serverId));
    }

    public Optional<String> applyUsername(UUID uuid, String username, Instant fetchedAt) {
        return db.call(() -> db.applyUsername(uuid, username, fetchedAt));
    }

    @Override
    public void close() {
        db.close();
    }
}