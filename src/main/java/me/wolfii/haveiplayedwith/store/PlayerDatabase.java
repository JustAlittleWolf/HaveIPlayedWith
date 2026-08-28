package me.wolfii.haveiplayedwith.store;

import com.google.gson.Gson;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Single-file store for players you've been around, backed by H2 MVStore (pure Java, ~350 KB).
 * Each logical write is committed from the database thread, not the client thread.
 */
public final class PlayerDatabase implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private final StoreWorker worker;
    private final MVMap<String, String> players;
    private final MVMap<String, String> history;
    private final MVMap<String, String> nameIndex;
    private final MVMap<String, String> playDays;
    private final MVMap<String, String> playSessions;
    private final MVMap<String, String> playServers;
    private final MVMap<String, String> mojangUuid;
    private final MVMap<String, String> mojangName;
    private final MVMap<String, String> crafty;
    private final MVMap<String, String> imports;

    public PlayerDatabase(Path file) {
        try {
            Files.createDirectories(file.getParent());
            MVStore store = new MVStore.Builder()
                .fileName(file.toAbsolutePath().toString())
                .compress()
                .autoCommitDisabled()
                .open();
            this.worker = new StoreWorker(store);
            this.players = store.openMap("players");
            this.history = store.openMap("username_history");
            this.nameIndex = store.openMap("name_index");
            this.playDays = store.openMap("play_days");
            this.playSessions = store.openMap("play_sessions");
            this.playServers = store.openMap("play_servers");
            this.mojangUuid = store.openMap("mojang_uuid");
            this.mojangName = store.openMap("mojang_name");
            this.crafty = store.openMap("crafty");
            this.imports = store.openMap("import_progress");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
        }
    }

    private static String blankToNull(String note) {
        return note == null || note.isBlank() ? null : note;
    }

    public <T> T call(Callable<T> task) {
        return worker.call(task);
    }

    public void run(DbWork task) {
        worker.run(task);
    }

    public List<PlayerSnapshot> findByName(String name) {
        return call(() -> findByNameOnThread(name));
    }

    public Optional<PlayerSnapshot> get(UUID uuid) {
        return call(() -> loadSnapshot(uuid));
    }

    public void setNote(UUID uuid, String username, String note) {
        run(() -> {
            ensurePlayerRow(uuid, username);
            StoreRows.PlayerRow row = playerRow(uuid);
            String cleaned = blankToNull(note);
            Long takenAt = cleaned == null ? null : Instant.now().toEpochMilli();
            putPlayer(uuid, row.withNote(cleaned, takenAt));
        });
    }

    public void recordLivePlay(UUID uuid, String username, LocalDate day, String sessionId, String serverId) {
        run(() -> {
            Instant now = Instant.now();
            ensurePlayerRow(uuid, username);
            touchUsername(uuid, username, now);
            setCurrentUsername(uuid, username);
            addSession(uuid, sessionId);
            addMinute(uuid, day, serverId);
        });
    }

    /**
     * Only {@code seenUsername} enters the name history: the name Crafty reports as current was
     * never actually seen by this player, so it gets no "last seen" timestamp.
     */
    public void recordImportedSighting(UUID uuid, String seenUsername, String currentUsername, LocalDate day, String sessionId, Instant seenAt) {
        run(() -> {
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

    public void applyMojangUsername(UUID uuid, String username, Instant fetchedAt) {
        run(() -> {
            if (playerRow(uuid) == null) {
                return;
            }
            touchUsername(uuid, username, fetchedAt);
            setCurrentUsername(uuid, username);
        });
    }

    public Optional<MojangCache> mojangCache(UUID uuid) {
        return call(() -> {
            String raw = mojangUuid.get(StoreKeys.uuid(uuid));
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.InstantRow row = GSON.fromJson(raw, StoreRows.InstantRow.class);
            return Optional.of(new MojangCache(row.username(), Instant.ofEpochMilli(row.fetchedAt())));
        });
    }

    public void putMojangCache(UUID uuid, String username, Instant fetchedAt) {
        run(() -> mojangUuid.put(StoreKeys.uuid(uuid), GSON.toJson(new StoreRows.InstantRow(username, fetchedAt.toEpochMilli()))));
    }

    public Optional<MojangNameCache> mojangNameCache(String usernameLower) {
        return call(() -> {
            String raw = mojangName.get(usernameLower);
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.NameCacheRow row = GSON.fromJson(raw, StoreRows.NameCacheRow.class);
            UUID uuid = row.uuid() == null ? null : UUID.fromString(row.uuid());
            return Optional.of(new MojangNameCache(uuid, row.username(), Instant.ofEpochMilli(row.fetchedAt())));
        });
    }

    public void putMojangNameCache(String usernameLower, MojangNameCache cache) {
        run(() -> mojangName.put(usernameLower, GSON.toJson(new StoreRows.NameCacheRow(
            cache.uuid() == null ? null : cache.uuid().toString(),
            cache.username(),
            cache.fetchedAt().toEpochMilli()
        ))));
    }

    public Optional<CraftyCache> craftyCache(String usernameLower) {
        return call(() -> {
            String raw = crafty.get(usernameLower);
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.CraftyRow row = GSON.fromJson(raw, StoreRows.CraftyRow.class);
            return Optional.of(new CraftyCache(
                row.uuid(),
                row.currentUsername(),
                row.usernamesJson(),
                row.valid(),
                Instant.ofEpochMilli(row.fetchedAt())
            ));
        });
    }

    public void putCraftyCache(String usernameLower, CraftyCache cache) {
        run(() -> crafty.put(usernameLower, GSON.toJson(new StoreRows.CraftyRow(
            cache.uuid(),
            cache.currentUsername(),
            cache.usernamesJson(),
            cache.valid(),
            cache.fetchedAt().toEpochMilli()
        ))));
    }

    public Optional<ImportProgress> importProgress(String source) {
        return call(() -> {
            String raw = imports.get(source);
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.ImportRow row = GSON.fromJson(raw, StoreRows.ImportRow.class);
            return Optional.of(new ImportProgress(
                source,
                row.processed(),
                row.total(),
                row.lastTimestamp() == null ? null : LocalDateTime.parse(row.lastTimestamp()),
                row.skip(),
                row.status(),
                row.silenced()
            ));
        });
    }

    public void saveImportProgress(ImportProgress progress) {
        run(() -> imports.put(progress.source(), GSON.toJson(new StoreRows.ImportRow(
            progress.processed(),
            progress.total(),
            progress.lastTimestamp() == null ? null : progress.lastTimestamp().toString(),
            progress.skip(),
            progress.status(),
            progress.silenced()
        ))));
    }

    @Override
    public void close() {
        worker.close();
    }

    private List<PlayerSnapshot> findByNameOnThread(String name) {
        String raw = nameIndex.get(StoreKeys.nameIndex(name));
        if (raw == null) {
            return List.of();
        }
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (String id : GSON.fromJson(raw, String[].class)) {
            loadSnapshot(UUID.fromString(id)).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    private Optional<PlayerSnapshot> loadSnapshot(UUID uuid) {
        StoreRows.PlayerRow row = playerRow(uuid);
        if (row == null) {
            return Optional.empty();
        }
        List<SeenName> names = new ArrayList<>();
        String prefix = StoreKeys.prefix(uuid);
        Iterator<String> keys = history.keyIterator(prefix);
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            StoreRows.HistoryRow seen = GSON.fromJson(history.get(key), StoreRows.HistoryRow.class);
            names.add(new SeenName(seen.username(), Instant.ofEpochMilli(seen.lastSeen())));
        }
        names.sort(Comparator.comparing(SeenName::lastSeen).reversed());
        List<ServerPlay> servers = new ArrayList<>();
        Iterator<String> serverKeys = playServers.keyIterator(prefix);
        while (serverKeys.hasNext()) {
            String key = serverKeys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            servers.add(new ServerPlay(key.substring(prefix.length()), Long.parseLong(playServers.get(key))));
        }
        servers.sort(Comparator.comparingLong(ServerPlay::minutes).reversed().thenComparing(ServerPlay::serverId));
        Optional<Instant> noteTakenAt = Optional.ofNullable(row.noteTakenAt()).map(Instant::ofEpochMilli);
        Optional<String> note = Optional.ofNullable(row.note()).filter(value -> !value.isBlank());
        if (note.isEmpty()) {
            noteTakenAt = Optional.empty();
        }
        return Optional.of(new PlayerSnapshot(
            uuid,
            row.currentUsername(),
            note,
            noteTakenAt,
            row.totalMinutes(),
            row.sessionCount(),
            StoreKeys.countPrefix(playDays, prefix),
            lastPlayedBefore(prefix, LocalDate.now()),
            names,
            servers
        ));
    }

    private Optional<LocalDate> lastPlayedBefore(String prefix, LocalDate excluded) {
        LocalDate latest = null;
        Iterator<String> keys = playDays.keyIterator(prefix);
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
        String raw = players.get(StoreKeys.uuid(uuid));
        return raw == null ? null : GSON.fromJson(raw, StoreRows.PlayerRow.class);
    }

    private void putPlayer(UUID uuid, StoreRows.PlayerRow row) {
        players.put(StoreKeys.uuid(uuid), GSON.toJson(row));
    }

    private void ensurePlayerRow(UUID uuid, String username) {
        if (playerRow(uuid) != null) {
            return;
        }
        putPlayer(uuid, new StoreRows.PlayerRow(username, null, null, 0, 0));
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
        String raw = history.get(key);
        long millis = seenAt.toEpochMilli();
        if (raw != null) {
            StoreRows.HistoryRow existing = GSON.fromJson(raw, StoreRows.HistoryRow.class);
            if (existing.lastSeen() >= millis) {
                indexName(uuid, existing.username());
                return;
            }
        }
        history.put(key, GSON.toJson(new StoreRows.HistoryRow(username, millis)));
        indexName(uuid, username);
    }

    private void indexName(UUID uuid, String username) {
        String key = StoreKeys.nameIndex(username);
        String id = uuid.toString();
        String raw = nameIndex.get(key);
        List<String> ids = raw == null ? new ArrayList<>() : new ArrayList<>(List.of(GSON.fromJson(raw, String[].class)));
        if (!ids.contains(id)) {
            ids.add(id);
            nameIndex.put(key, GSON.toJson(ids));
        }
    }

    private void addSession(UUID uuid, String sessionId) {
        String key = StoreKeys.session(uuid, sessionId);
        if (playSessions.containsKey(key)) {
            return;
        }
        playSessions.put(key, "1");
        StoreRows.PlayerRow row = playerRow(uuid);
        putPlayer(uuid, row.plusSession());
    }

    private void addMinute(UUID uuid, LocalDate day, String serverId) {
        ensurePlayDay(uuid, day);
        String key = StoreKeys.playDay(uuid, day);
        long minutes = Long.parseLong(playDays.get(key)) + 1;
        playDays.put(key, Long.toString(minutes));
        addServerMinute(uuid, serverId);
        StoreRows.PlayerRow row = playerRow(uuid);
        putPlayer(uuid, row.plusMinute());
    }

    private void addServerMinute(UUID uuid, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        String key = StoreKeys.server(uuid, serverId);
        String raw = playServers.get(key);
        long minutes = raw == null ? 1 : Long.parseLong(raw) + 1;
        playServers.put(key, Long.toString(minutes));
    }

    private void ensurePlayDay(UUID uuid, LocalDate day) {
        playDays.putIfAbsent(StoreKeys.playDay(uuid, day), "0");
    }

    @FunctionalInterface
    public interface DbWork {
        void run() throws Exception;
    }
}
