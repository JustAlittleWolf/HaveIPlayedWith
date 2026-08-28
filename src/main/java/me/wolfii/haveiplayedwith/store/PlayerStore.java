package me.wolfii.haveiplayedwith.store;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-file store for players you've been around, backed by H2 MVStore (pure Java, ~350 KB).
 * Each logical write is committed from the database thread, not the client thread.
 */
public final class PlayerStore implements AutoCloseable {
    private final StoreSession session;
    private final MojangProfileStore mojangProfiles;
    private final ImportProgressStore importProgress;

    public PlayerStore(Path file) {
        this.session = StoreSession.open(file);
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
            String raw = session.playSessions.get(StoreKeys.session(uuid, sessionId));
            if (raw == null || raw.isBlank()) {
                return 0L;
            }
            return Long.parseLong(raw);
        });
    }

    public void setNote(UUID uuid, String username, String note) {
        session.run(() -> {
            ensurePlayerRow(uuid, username);
            StoreRows.PlayerRow row = playerRow(uuid);
            String cleaned = blankToEmpty(note);
            long takenAt = cleaned.isEmpty() ? 0L : Instant.now().toEpochMilli();
            putPlayer(uuid, row.withNote(cleaned, takenAt));
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
            ensurePlayDay(uuid, day);
        });
    }

    public Optional<String> applyMojangUsername(UUID uuid, String username, Instant fetchedAt) {
        return session.call(() -> {
            if (playerRow(uuid) == null) {
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
        String raw = session.nameIndex.get(StoreKeys.nameIndex(name));
        if (raw == null) {
            return List.of();
        }
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (String id : session.gson().fromJson(raw, String[].class)) {
            loadSnapshot(UUID.fromString(id)).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    private Optional<PlayerSnapshot> loadSnapshot(UUID uuid) {
        StoreRows.PlayerRow row = playerRow(uuid);
        if (row == null) {
            return Optional.empty();
        }
        List<SeenName> names = loadHistory(uuid);
        List<ServerPlay> servers = new ArrayList<>();
        String prefix = StoreKeys.prefix(uuid);
        Iterator<String> serverKeys = session.playServers.keyIterator(prefix);
        while (serverKeys.hasNext()) {
            String key = serverKeys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            servers.add(new ServerPlay(key.substring(prefix.length()), Long.parseLong(session.playServers.get(key))));
        }
        servers.sort(Comparator.comparingLong(ServerPlay::minutes).reversed().thenComparing(ServerPlay::serverId));
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
            StoreKeys.countPrefix(session.playDays, prefix),
            lastPlayedBefore(prefix, LocalDate.now()),
            names,
            servers
        ));
    }

    private List<SeenName> loadHistory(UUID uuid) {
        List<SeenName> names = new ArrayList<>();
        String prefix = StoreKeys.prefix(uuid);
        Iterator<String> keys = session.history.keyIterator(prefix);
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            StoreRows.HistoryRow seen = session.gson().fromJson(session.history.get(key), StoreRows.HistoryRow.class);
            names.add(new SeenName(seen.username(), Instant.ofEpochMilli(seen.lastSeen())));
        }
        names.sort(Comparator.comparing(SeenName::lastSeen).reversed());
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

    private Optional<LocalDate> lastPlayedBefore(String prefix, LocalDate excluded) {
        LocalDate latest = null;
        Iterator<String> keys = session.playDays.keyIterator(prefix);
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            LocalDate day = StoreKeys.playDayOf(key, prefix);
            if (day.equals(excluded)) {
                continue;
            }
            if (latest == null || day.isAfter(latest)) {
                latest = day;
            }
        }
        return Optional.ofNullable(latest);
    }

    private StoreRows.PlayerRow playerRow(UUID uuid) {
        String raw = session.players.get(StoreKeys.uuid(uuid));
        return raw == null ? null : session.gson().fromJson(raw, StoreRows.PlayerRow.class);
    }

    private void putPlayer(UUID uuid, StoreRows.PlayerRow row) {
        session.players.put(StoreKeys.uuid(uuid), session.gson().toJson(row));
    }

    private void ensurePlayerRow(UUID uuid, String username) {
        if (playerRow(uuid) != null) {
            return;
        }
        putPlayer(uuid, new StoreRows.PlayerRow(username, "", 0L, 0, 0));
        indexName(uuid, username);
    }

    private void setCurrentUsername(UUID uuid, String username) {
        StoreRows.PlayerRow row = playerRow(uuid);
        if (row == null) {
            return;
        }
        putPlayer(uuid, row.withUsername(username));
        indexName(uuid, username);
    }

    private void touchUsername(UUID uuid, String username, Instant seenAt) {
        String key = StoreKeys.history(uuid, username);
        String raw = session.history.get(key);
        long millis = seenAt.toEpochMilli();
        if (raw != null) {
            StoreRows.HistoryRow existing = session.gson().fromJson(raw, StoreRows.HistoryRow.class);
            if (existing.lastSeen() >= millis) {
                indexName(uuid, existing.username());
                return;
            }
        }
        session.history.put(key, session.gson().toJson(new StoreRows.HistoryRow(username, millis)));
        indexName(uuid, username);
    }

    private void indexName(UUID uuid, String username) {
        String key = StoreKeys.nameIndex(username);
        String id = uuid.toString();
        String raw = session.nameIndex.get(key);
        List<String> ids = raw == null ? new ArrayList<>() : new ArrayList<>(List.of(session.gson().fromJson(raw, String[].class)));
        if (!ids.contains(id)) {
            ids.add(id);
            session.nameIndex.put(key, session.gson().toJson(ids));
        }
    }

    private void addSession(UUID uuid, String sessionId) {
        String key = StoreKeys.session(uuid, sessionId);
        if (session.playSessions.containsKey(key)) {
            return;
        }
        session.playSessions.put(key, "0");
        StoreRows.PlayerRow row = playerRow(uuid);
        putPlayer(uuid, row.plusSession());
    }

    private void addSessionMinute(UUID uuid, String sessionId) {
        String key = StoreKeys.session(uuid, sessionId);
        String raw = session.playSessions.get(key);
        if (raw == null) {
            session.playSessions.put(key, "1");
            StoreRows.PlayerRow row = playerRow(uuid);
            putPlayer(uuid, row.plusSession());
            return;
        }
        session.playSessions.put(key, Long.toString(Long.parseLong(raw) + 1));
    }

    private void addMinute(UUID uuid, LocalDate day, String serverId) {
        ensurePlayDay(uuid, day);
        String key = StoreKeys.playDay(uuid, day);
        long minutes = Long.parseLong(session.playDays.get(key)) + 1;
        session.playDays.put(key, Long.toString(minutes));
        addServerMinute(uuid, serverId);
        StoreRows.PlayerRow row = playerRow(uuid);
        putPlayer(uuid, row.plusMinute());
    }

    private void addServerMinute(UUID uuid, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        String key = StoreKeys.server(uuid, serverId);
        String raw = session.playServers.get(key);
        long minutes = raw == null ? 1 : Long.parseLong(raw) + 1;
        session.playServers.put(key, Long.toString(minutes));
    }

    private void ensurePlayDay(UUID uuid, LocalDate day) {
        session.playDays.putIfAbsent(StoreKeys.playDay(uuid, day), "0");
    }
}
