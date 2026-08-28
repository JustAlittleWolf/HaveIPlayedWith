package me.wolfii.haveiplayedwith.store;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player store backed by SmallSQL (pure Java JDBC, ~340 KB). Each logical write is
 * committed from the database thread, not the client thread.
 */
public final class PlayerStore implements AutoCloseable {
    private final StoreSession session;
    private final MojangProfileStore mojangProfiles;
    private final ImportProgressStore importProgress;

    public PlayerStore(Path directory) {
        this.session = StoreSession.open(directory);
        this.mojangProfiles = new MojangProfileStore(session);
        this.importProgress = new ImportProgressStore(session);
    }

    public MojangProfileStore mojangProfiles() {
        return mojangProfiles;
    }

    public ImportProgressStore importProgress() {
        return importProgress;
    }

    private static String blankToEmpty(String note) {
        return note == null || note.isBlank() ? "" : note;
    }

    public List<PlayerSnapshot> findByName(String name) {
        return session.call(() -> findByNameOnThread(name));
    }

    public Optional<PlayerSnapshot> get(UUID uuid) {
        return session.call(() -> loadSnapshot(uuid));
    }

    public long sessionMinutes(UUID uuid, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        return session.call(() -> {
            Long minutes = session.db.sessionMinutes(uuid, sessionId);
            return minutes == null ? 0L : minutes;
        });
    }

    public void setNote(UUID uuid, String username, String note) {
        session.run(() -> {
            ensurePlayerRow(uuid, username);
            StoreRows.PlayerRow row = session.db.player(uuid);
            String cleaned = blankToEmpty(note);
            long takenAt = cleaned.isEmpty() ? 0L : Instant.now().toEpochMilli();
            session.db.putPlayer(uuid, row.withNote(cleaned, takenAt));
        });
    }

    /**
     * @return the last name this player was seen as, when the new username is different
     */
    public Optional<String> recordLivePlay(UUID uuid, String username, LocalDate day, String sessionId, String serverId) {
        return session.call(() -> {
            Optional<String> previousName = previousSeenNameIfDifferent(uuid, username);
            Instant now = Instant.now();
            ensurePlayerRow(uuid, username);
            touchUsername(uuid, username, now);
            setCurrentUsername(uuid, username);
            addSessionMinute(uuid, sessionId);
            addMinute(uuid, day, serverId);
            return previousName;
        });
    }

    /**
     * Only {@code seenUsername} enters the name history: the name Crafty reports as current was
     * never actually seen by this player, so it gets no "last seen" timestamp.
     */
    public void recordImportedSighting(UUID uuid, String seenUsername, String currentUsername, LocalDate day, String sessionId, Instant seenAt) {
        session.run(() -> {
            String display = currentUsername == null || currentUsername.isBlank() ? seenUsername : currentUsername;
            ensurePlayerRow(uuid, display);
            if (currentUsername != null && !currentUsername.isBlank()) {
                setCurrentUsername(uuid, currentUsername);
            }
            touchUsername(uuid, seenUsername, seenAt);
            addSession(uuid, sessionId);
            session.db.putPlayDayIfAbsent(uuid, day);
        });
    }

    public Optional<String> applyMojangUsername(UUID uuid, String username, Instant fetchedAt) {
        return session.call(() -> {
            if (session.db.player(uuid) == null) {
                return Optional.empty();
            }
            Optional<String> previousName = previousSeenNameIfDifferent(uuid, username);
            touchUsername(uuid, username, fetchedAt);
            setCurrentUsername(uuid, username);
            return previousName;
        });
    }

    @Override
    public void close() {
        session.close();
    }

    private List<PlayerSnapshot> findByNameOnThread(String name) {
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (UUID uuid : session.db.findNameIndex(name)) {
            loadSnapshot(uuid).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    private Optional<PlayerSnapshot> loadSnapshot(UUID uuid) {
        StoreRows.PlayerRow row = session.db.player(uuid);
        if (row == null) {
            return Optional.empty();
        }
        List<SeenName> names = loadHistory(uuid);
        List<ServerPlay> servers = session.db.listServers(uuid);
        Optional<String> note = Optional.of(row.note()).filter(value -> !value.isBlank());
        Optional<Instant> noteTakenAt = note.isEmpty() || row.noteTakenAt() == 0L
            ? Optional.empty()
            : Optional.of(Instant.ofEpochMilli(row.noteTakenAt()));
        return Optional.of(new PlayerSnapshot(
            uuid,
            row.currentUsername(),
            note,
            noteTakenAt,
            row.totalMinutes(),
            row.sessionCount(),
            session.db.countPlayDays(uuid),
            session.db.lastPlayedBefore(uuid, LocalDate.now()),
            names,
            servers
        ));
    }

    private List<SeenName> loadHistory(UUID uuid) {
        List<SeenName> names = new ArrayList<>();
        for (StoreRows.HistoryRow seen : session.db.listHistory(uuid)) {
            names.add(new SeenName(seen.username(), Instant.ofEpochMilli(seen.lastSeen())));
        }
        return names;
    }

    private Optional<String> previousSeenNameIfDifferent(UUID uuid, String username) {
        String latest = null;
        Instant latestAt = null;
        for (SeenName seen : loadHistory(uuid)) {
            if (latestAt == null || seen.lastSeen().isAfter(latestAt)) {
                latest = seen.username();
                latestAt = seen.lastSeen();
            }
        }
        if (latest == null || latest.equalsIgnoreCase(username)) {
            return Optional.empty();
        }
        return Optional.of(latest);
    }

    private void ensurePlayerRow(UUID uuid, String username) {
        if (session.db.player(uuid) != null) {
            return;
        }
        session.db.putPlayer(uuid, new StoreRows.PlayerRow(username, "", 0L, 0, 0));
        session.db.indexName(uuid, username);
    }

    private void setCurrentUsername(UUID uuid, String username) {
        StoreRows.PlayerRow row = session.db.player(uuid);
        if (row == null) {
            return;
        }
        session.db.putPlayer(uuid, row.withUsername(username));
        session.db.indexName(uuid, username);
    }

    private void touchUsername(UUID uuid, String username, Instant seenAt) {
        StoreRows.HistoryRow existing = session.db.history(uuid, username);
        long millis = seenAt.toEpochMilli();
        if (existing != null) {
            if (existing.lastSeen() >= millis) {
                session.db.indexName(uuid, existing.username());
                return;
            }
        }
        session.db.putHistory(uuid, new StoreRows.HistoryRow(username, millis));
        session.db.indexName(uuid, username);
    }

    private void addSession(UUID uuid, String sessionId) {
        if (session.db.hasSession(uuid, sessionId)) {
            return;
        }
        session.db.putSession(uuid, sessionId, 0L);
        StoreRows.PlayerRow row = session.db.player(uuid);
        session.db.putPlayer(uuid, row.plusSession());
    }

    private void addSessionMinute(UUID uuid, String sessionId) {
        Long raw = session.db.sessionMinutes(uuid, sessionId);
        if (raw == null) {
            session.db.putSession(uuid, sessionId, 1L);
            StoreRows.PlayerRow row = session.db.player(uuid);
            session.db.putPlayer(uuid, row.plusSession());
            return;
        }
        session.db.putSession(uuid, sessionId, raw + 1);
    }

    private void addMinute(UUID uuid, LocalDate day, String serverId) {
        session.db.putPlayDayIfAbsent(uuid, day);
        long minutes = session.db.playDayMinutes(uuid, day) + 1;
        session.db.putPlayDay(uuid, day, minutes);
        addServerMinute(uuid, serverId);
        StoreRows.PlayerRow row = session.db.player(uuid);
        session.db.putPlayer(uuid, row.plusMinute());
    }

    private void addServerMinute(UUID uuid, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        Long raw = session.db.serverMinutes(uuid, serverId);
        session.db.putServer(uuid, serverId, raw == null ? 1 : raw + 1);
    }
}
