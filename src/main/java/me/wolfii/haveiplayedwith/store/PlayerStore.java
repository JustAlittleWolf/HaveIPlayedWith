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
 * MVStore is a transactional B-tree; rows are packed binary values and counters are longs,
 * not JSON documents. Each logical write is committed from the database thread, not the client thread.
 */
public final class PlayerStore implements AutoCloseable {
    private final StoreSession session;
    private final MojangProfileStore mojangProfiles;
    private final CraftyProfileStore craftyProfiles;
    private final ImportProgressStore importProgress;

    public PlayerStore(Path file) {
        this.session = StoreSession.open(file);
        this.mojangProfiles = new MojangProfileStore(session);
        this.craftyProfiles = new CraftyProfileStore(session);
        this.importProgress = new ImportProgressStore(session);
    }

    public MojangProfileStore mojangProfiles() {
        return mojangProfiles;
    }

    public CraftyProfileStore craftyProfiles() {
        return craftyProfiles;
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
            addSession(uuid, sessionId);
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
        String prefix = StoreKeys.nameIndexPrefix(name);
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        Iterator<String> keys = session.nameIndex.keyIterator(prefix);
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            loadSnapshot(StoreKeys.nameIndexUuid(key, prefix)).ifPresent(snapshots::add);
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
            servers.add(new ServerPlay(key.substring(prefix.length()), session.playServers.get(key)));
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
            StoreRows.HistoryRow seen = StoreCodec.history(session.history.get(key));
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
        byte[] raw = session.players.get(StoreKeys.uuid(uuid));
        return raw == null ? null : StoreCodec.player(raw);
    }

    private void putPlayer(UUID uuid, StoreRows.PlayerRow row) {
        session.players.put(StoreKeys.uuid(uuid), StoreCodec.player(row));
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
        byte[] raw = session.history.get(key);
        long millis = seenAt.toEpochMilli();
        if (raw != null) {
            StoreRows.HistoryRow existing = StoreCodec.history(raw);
            if (existing.lastSeen() >= millis) {
                indexName(uuid, existing.username());
                return;
            }
        }
        session.history.put(key, StoreCodec.history(new StoreRows.HistoryRow(username, millis)));
        indexName(uuid, username);
    }

    private void indexName(UUID uuid, String username) {
        session.nameIndex.putIfAbsent(StoreKeys.nameIndex(username, uuid), 1L);
    }

    private void addSession(UUID uuid, String sessionId) {
        String key = StoreKeys.session(uuid, sessionId);
        if (session.playSessions.containsKey(key)) {
            return;
        }
        session.playSessions.put(key, 1L);
        StoreRows.PlayerRow row = playerRow(uuid);
        putPlayer(uuid, row.plusSession());
    }

    private void addMinute(UUID uuid, LocalDate day, String serverId) {
        ensurePlayDay(uuid, day);
        String key = StoreKeys.playDay(uuid, day);
        long minutes = session.playDays.get(key) + 1;
        session.playDays.put(key, minutes);
        addServerMinute(uuid, serverId);
        StoreRows.PlayerRow row = playerRow(uuid);
        putPlayer(uuid, row.plusMinute());
    }

    private void addServerMinute(UUID uuid, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        String key = StoreKeys.server(uuid, serverId);
        Long raw = session.playServers.get(key);
        long minutes = raw == null ? 1 : raw + 1;
        session.playServers.put(key, minutes);
    }

    private void ensurePlayDay(UUID uuid, LocalDate day) {
        session.playDays.putIfAbsent(StoreKeys.playDay(uuid, day), 0L);
    }
}
