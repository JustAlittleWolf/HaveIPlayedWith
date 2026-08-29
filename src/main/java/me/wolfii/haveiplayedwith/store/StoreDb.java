package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.ByteArrayDataType;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Player and profile rows in one MVStore file. Live minute ticks mutate in-memory
 * {@link PlayerRecord}s and are written on a timer, so MVStore is not copy-on-write
 * rewritten every calendar minute.
 */
final class StoreDb implements AutoCloseable {
    private static final MVMap.Builder<byte[], byte[]> BYTES = new MVMap.Builder<byte[], byte[]>()
        .keyType(ByteArrayDataType.INSTANCE)
        .valueType(ByteArrayDataType.INSTANCE);

    private final Path file;
    private final StoreWorker worker;
    private MVStore store;
    private MVMap<byte[], byte[]> players;
    private MVMap<byte[], byte[]> profiles;
    private MVMap<byte[], byte[]> misses;
    private final Map<UUID, PlayerRecord> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();

    private StoreDb(Path file, StoreWorker worker, MVStore store) {
        this.file = file;
        this.worker = worker;
        bind(store);
    }

    static StoreDb open(Path file) {
        try {
            StoreDb db = new StoreDb(file, new StoreWorker(), StoreMv.open(file));
            db.worker.scheduleFlush(db::flushPending);
            db.worker.scheduleCompact(db::compactIfWorthwhile);
            return db;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
        }
    }

    private void bind(MVStore store) {
        this.store = store;
        this.players = store.openMap("players", BYTES);
        this.profiles = store.openMap("profiles", BYTES);
        this.misses = store.openMap("misses", BYTES);
        cache.clear();
        dirty.clear();
        worker.use(store);
    }

    /**
     * Rewrites the store file onto the database thread when leftover MVStore
     * chunks would actually shrink it. Maps are rebound after the copy.
     */
    void compactIfWorthwhile() {
        if (!StoreMv.shouldCompact(store)) {
            return;
        }
        flushPending();
        long before = StoreMv.size(file);
        try {
            store.commit();
            store.close();
        } catch (Exception e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith store before compact", e);
            try {
                bind(StoreMv.open(file));
            } catch (Exception reopen) {
                e.addSuppressed(reopen);
                throw new IllegalStateException("Failed to reopen HaveIPlayedWith store after compact close", e);
            }
            return;
        }
        try {
            StoreMv.compact(file);
        } catch (Exception e) {
            ModLog.LOGGER.warn("HaveIPlayedWith store compact failed", e);
            StoreMv.cleanup(file);
        }
        bind(StoreMv.open(file));
        long after = StoreMv.size(file);
        if (after < before) {
            ModLog.LOGGER.info("Compacted HaveIPlayedWith store from {} to {} bytes", before, after);
        }
    }

    boolean hasReclaimableSpace() {
        return StoreMv.shouldCompact(store);
    }

    void commit() {
        store.commit();
    }

    <T> T call(Callable<T> task) {
        return worker.call(task);
    }

    void run(StoreWork task) {
        worker.run(task);
    }

    @Override
    public void close() {
        worker.close(() -> {
            flushPending();
            if (store != null && !store.isClosed()) {
                store.close();
            }
        });
    }

    boolean hasPlayer(UUID uuid) {
        return cache.containsKey(uuid) || players.containsKey(StoreCodec.uuidBytes(uuid));
    }

    void ensurePlayer(UUID uuid, String username) {
        if (hasPlayer(uuid)) {
            return;
        }
        PlayerRecord player = new PlayerRecord(uuid);
        player.setCurrentUsername(username);
        cache.put(uuid, player);
        dirty.add(uuid);
    }

    void setNote(UUID uuid, String note, long noteTakenAt) {
        PlayerRecord player = player(uuid);
        player.setNote(note, noteTakenAt);
        dirty.add(uuid);
    }

    Optional<String> recordLivePlay(UUID uuid, String username, LocalDate day, String sessionId, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId");
        }
        PlayerRecord player = player(uuid);
        Optional<String> previousName = player.previousNameIfDifferent(username);
        player.setCurrentUsername(username);
        player.touchName(username, Instant.now());
        player.credit(day, sessionId, serverId);
        cache.put(uuid, player);
        dirty.add(uuid);
        return previousName;
    }

    Optional<String> applyUsername(UUID uuid, String username, Instant fetchedAt) {
        if (!hasPlayer(uuid)) {
            return Optional.empty();
        }
        PlayerRecord player = player(uuid);
        Optional<String> previousName = player.previousNameIfDifferent(username);
        player.touchName(username, fetchedAt);
        player.setCurrentUsername(username);
        dirty.add(uuid);
        return previousName;
    }

    List<PlayerSnapshot> findByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (PlayerRecord player : allPlayers()) {
            if (player.matchesName(lower)) {
                snapshots.add(player.snapshot());
            }
        }
        return snapshots;
    }

    Optional<PlayerSnapshot> snapshot(UUID uuid) {
        if (!hasPlayer(uuid)) {
            return Optional.empty();
        }
        return Optional.of(player(uuid).snapshot());
    }

    Long sessionMinutes(UUID uuid, String sessionId) {
        if (!hasPlayer(uuid)) {
            return null;
        }
        long minutes = player(uuid).minutesForSession(sessionId);
        return minutes == 0L ? null : minutes;
    }

    Optional<ProfileMapping> profileByUuid(UUID uuid) {
        byte[] stored = profiles.get(StoreCodec.uuidBytes(uuid));
        return stored == null ? Optional.empty() : Optional.of(StoreCodec.decodeProfile(uuid, stored));
    }

    Optional<ProfileMapping> profileByName(String usernameLower) {
        String lower = usernameLower.toLowerCase(Locale.ROOT);
        ProfileMapping newest = null;
        for (Map.Entry<byte[], byte[]> row : profiles.entrySet()) {
            ProfileMapping mapping = StoreCodec.decodeProfile(StoreCodec.uuid(row.getKey()), row.getValue());
            if (mapping.username() == null || !mapping.username().toLowerCase(Locale.ROOT).equals(lower)) {
                continue;
            }
            if (newest == null || mapping.lastValid().isAfter(newest.lastValid())) {
                newest = mapping;
            }
        }
        byte[] miss = misses.get(StoreCodec.nameKey(lower));
        if (miss != null) {
            Instant lastValid = Instant.ofEpochMilli(StoreCodec.decodeMillis(miss));
            if (newest == null || lastValid.isAfter(newest.lastValid())) {
                newest = new ProfileMapping(null, lower, lastValid);
            }
        }
        return Optional.ofNullable(newest);
    }

    void putProfile(ProfileMapping mapping) {
        Instant lastValid = mapping.lastValid();
        if (mapping.uuid() != null) {
            String stored = mapping.username() == null ? "" : mapping.username();
            String lower = stored.isBlank() ? "" : stored.toLowerCase(Locale.ROOT);
            if (!lower.isBlank()) {
                misses.remove(StoreCodec.nameKey(lower));
            }
            profiles.put(StoreCodec.uuidBytes(mapping.uuid()), StoreCodec.encodeProfile(
                new ProfileMapping(mapping.uuid(), stored, lastValid)));
            return;
        }
        String lower = mapping.username() == null ? "" : mapping.username().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return;
        }
        misses.put(StoreCodec.nameKey(lower), StoreCodec.encodeMillis(lastValid.toEpochMilli()));
    }

    void flushPending() {
        if (dirty.isEmpty()) {
            return;
        }
        for (UUID uuid : dirty) {
            PlayerRecord player = cache.get(uuid);
            if (player != null) {
                players.put(StoreCodec.uuidBytes(uuid), StoreCodec.encodePlayer(player));
            }
        }
        dirty.clear();
        store.commit();
    }

    private PlayerRecord player(UUID uuid) {
        PlayerRecord cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        byte[] stored = players.get(StoreCodec.uuidBytes(uuid));
        PlayerRecord player = stored == null ? new PlayerRecord(uuid) : StoreCodec.decodePlayer(uuid, stored);
        cache.put(uuid, player);
        return player;
    }

    private List<PlayerRecord> allPlayers() {
        Map<UUID, PlayerRecord> all = new HashMap<>(cache);
        for (byte[] key : players.keySet()) {
            UUID uuid = StoreCodec.uuid(key);
            all.putIfAbsent(uuid, player(uuid));
        }
        return List.copyOf(all.values());
    }
}