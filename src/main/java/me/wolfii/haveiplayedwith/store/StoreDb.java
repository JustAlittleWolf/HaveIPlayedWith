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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;

/**
 * Player and profile rows in one MVStore file. A player change is visible
 * immediately and its row write is queued. Queued rows are written a few at a
 * time so a tab-list burst does not hit the file at once. {@link #close()}
 * writes anything still queued. H2 auto-commit then flushes and auto-compacts
 * in the background.
 */
final class StoreDb implements AutoCloseable {
    /** Player rows written on each paced turn. */
    static final int WRITES_PER_TURN = 10;
    /**
     * Pause between write turns. A full {@link WriteQueue} therefore takes about
     * five seconds to reach the map ({@code 500 * 100ms / 10}).
     */
    static final int WRITE_TURN_MS = 100;
    private static final MVMap.Builder<byte[], byte[]> BYTES = new MVMap.Builder<byte[], byte[]>()
        .keyType(ByteArrayDataType.INSTANCE)
        .valueType(ByteArrayDataType.INSTANCE);

    private final StoreWorker worker;
    private MVStore store;
    private MVMap<byte[], byte[]> players;
    private MVMap<byte[], byte[]> profiles;
    private MVMap<byte[], byte[]> misses;
    private final Map<UUID, PlayerRecord> live = new HashMap<>();
    /** Activity minutes received before a verified player row exists. */
    private final Map<UUID, Map<PlayerActivity, Long>> pendingActivityMinutes = new HashMap<>();
    /** Player rows waiting to be written, oldest first. */
    private final WriteQueue pendingWrites = new WriteQueue();
    private ScheduledFuture<?> writeTurn;
    private boolean acceptingWrites = true;

    private StoreDb(StoreWorker worker, MVStore store) {
        this.worker = worker;
        bind(store);
    }

    static StoreDb open(Path file) {
        try {
            return new StoreDb(new StoreWorker(), StoreMv.open(file));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
        }
    }

    private void bind(MVStore store) {
        this.store = store;
        this.players = store.openMap("players", BYTES);
        this.profiles = store.openMap("profiles", BYTES);
        this.misses = store.openMap("misses", BYTES);
        live.clear();
        worker.use(store);
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
            try {
                flushPendingWrites();
            } finally {
                if (store != null && !store.isClosed()) {
                    store.close();
                }
            }
        });
    }

    int pendingPlayerWrites() {
        return pendingWrites.size();
    }

    /** True when the map already holds this player, ignoring a newer row still queued. */
    boolean playerRowStored(UUID uuid) {
        return players.containsKey(StoreCodec.uuidBytes(uuid));
    }

    boolean hasPlayer(UUID uuid) {
        return live.containsKey(uuid) || players.containsKey(StoreCodec.uuidBytes(uuid));
    }

    void ensurePlayer(UUID uuid, String username) {
        if (hasPlayer(uuid)) {
            return;
        }
        PlayerRecord player = new PlayerRecord(uuid);
        player.setCurrentUsername(username);
        save(player);
    }

    void setNote(UUID uuid, String note, long noteTakenAt) {
        PlayerRecord player = player(uuid);
        player.setNote(note, noteTakenAt);
        save(player);
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
        Map<PlayerActivity, Long> pending = pendingActivityMinutes.remove(uuid);
        if (pending != null) {
            pending.forEach(player::addActivityMinutes);
        }
        save(player);
        return previousName;
    }

    void recordActivityMinute(UUID uuid, PlayerActivity activity) {
        if (!hasPlayer(uuid)) {
            pendingActivityMinutes.computeIfAbsent(uuid, ignored -> new EnumMap<>(PlayerActivity.class))
                .merge(activity, 1L, Long::sum);
            return;
        }
        PlayerRecord player = player(uuid);
        player.addActivityMinutes(activity, 1);
        save(player);
    }

    Optional<String> applyUsername(UUID uuid, String username, Instant fetchedAt) {
        if (!hasPlayer(uuid)) {
            return Optional.empty();
        }
        PlayerRecord player = player(uuid);
        Optional<String> previousName = player.previousNameIfDifferent(username);
        player.touchName(username, fetchedAt);
        player.setCurrentUsername(username);
        save(player);
        return previousName;
    }

    List<PlayerSnapshot> findByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (PlayerRecord player : live.values()) {
            seen.add(player.uuid);
            if (player.matchesName(lower)) {
                snapshots.add(player.snapshot());
            }
        }
        for (Map.Entry<byte[], byte[]> row : players.entrySet()) {
            UUID uuid = StoreCodec.uuid(row.getKey());
            if (!seen.add(uuid)) {
                continue;
            }
            PlayerRecord player = StoreCodec.decodePlayer(uuid, row.getValue());
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

    private void save(PlayerRecord player) {
        live.put(player.uuid, player);
        if (!acceptingWrites) {
            writePlayer(player.uuid);
            return;
        }
        pendingWrites.add(player.uuid).ifPresent(this::writePlayer);
        scheduleWriteTurn();
    }

    private void scheduleWriteTurn() {
        if (!acceptingWrites || pendingWrites.size() == 0) {
            return;
        }
        if (writeTurn != null && !writeTurn.isDone()) {
            return;
        }
        writeTurn = worker.schedule(this::flushWriteTurn, WRITE_TURN_MS);
    }

    private void flushWriteTurn() {
        writeTurn = null;
        if (!acceptingWrites) {
            return;
        }
        for (int i = 0; i < WRITES_PER_TURN; i++) {
            Optional<UUID> next = pendingWrites.poll();
            if (next.isEmpty()) {
                return;
            }
            writePlayer(next.get());
        }
        scheduleWriteTurn();
    }

    /** Writes every queued row. Saves after this write straight through, so close cannot drop one. */
    private void flushPendingWrites() {
        acceptingWrites = false;
        if (writeTurn != null) {
            writeTurn.cancel(false);
            writeTurn = null;
        }
        for (UUID uuid : pendingWrites.drain()) {
            writePlayer(uuid);
        }
    }

    private void writePlayer(UUID uuid) {
        PlayerRecord player = live.get(uuid);
        if (player == null) {
            return;
        }
        try {
            players.put(StoreCodec.uuidBytes(uuid), StoreCodec.encodePlayer(player));
        } catch (RuntimeException e) {
            ModLog.LOGGER.warn("Failed to encode HaveIPlayedWith player {}", uuid, e);
        }
    }

    private PlayerRecord player(UUID uuid) {
        PlayerRecord cached = live.get(uuid);
        if (cached != null) {
            return cached;
        }
        byte[] stored = players.get(StoreCodec.uuidBytes(uuid));
        PlayerRecord player = stored == null ? new PlayerRecord(uuid) : StoreCodec.decodePlayer(uuid, stored);
        live.put(uuid, player);
        return player;
    }
}