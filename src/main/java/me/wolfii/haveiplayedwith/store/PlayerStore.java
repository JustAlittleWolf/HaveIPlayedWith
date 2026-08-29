package me.wolfii.haveiplayedwith.store;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player store backed by Nitrite. Each logical write is committed from the
 * database thread, not the client thread.
 */
public final class PlayerStore implements AutoCloseable {
    private final StoreDb db;
    private final MojangProfileStore mojangProfiles;
    private final ImportProgressStore importProgress;

    public PlayerStore(Path directory) {
        this.db = StoreDb.open(directory);
        this.mojangProfiles = new MojangProfileStore(db);
        this.importProgress = new ImportProgressStore(db);
    }

    public MojangProfileStore mojangProfiles() {
        return mojangProfiles;
    }

    public ImportProgressStore importProgress() {
        return importProgress;
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
        return db.call(() -> {
            Optional<String> previousName = db.previousSeenNameIfDifferent(uuid, username);
            db.ensurePlayer(uuid, username);
            db.touchUsername(uuid, username, Instant.now());
            db.setCurrentUsername(uuid, username);
            db.addSessionMinute(uuid, sessionId);
            db.addMinute(uuid, day, serverId);
            return previousName;
        });
    }

    /**
     * Only {@code seenUsername} enters the name history: the name Crafty reports as current was
     * never actually seen by this player, so it gets no "last seen" timestamp.
     */
    public void recordImportedSighting(UUID uuid, String seenUsername, String currentUsername, LocalDate day, String sessionId, Instant seenAt) {
        db.run(() -> {
            String display = currentUsername == null || currentUsername.isBlank() ? seenUsername : currentUsername;
            db.ensurePlayer(uuid, display);
            if (currentUsername != null && !currentUsername.isBlank()) {
                db.setCurrentUsername(uuid, currentUsername);
            }
            db.touchUsername(uuid, seenUsername, seenAt);
            db.addSession(uuid, sessionId);
            db.ensurePlayDay(uuid, day);
        });
    }

    public Optional<String> applyMojangUsername(UUID uuid, String username, Instant fetchedAt) {
        return db.call(() -> {
            if (!db.hasPlayer(uuid)) {
                return Optional.empty();
            }
            Optional<String> previousName = db.previousSeenNameIfDifferent(uuid, username);
            db.touchUsername(uuid, username, fetchedAt);
            db.setCurrentUsername(uuid, username);
            return previousName;
        });
    }

    @Override
    public void close() {
        db.close();
    }
}
