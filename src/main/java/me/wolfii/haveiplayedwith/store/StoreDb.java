package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.mvstore.MVStoreModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static me.wolfii.haveiplayedwith.store.StoreSchema.CURRENT_USERNAME;
import static me.wolfii.haveiplayedwith.store.StoreSchema.DAYS_PLAYED;
import static me.wolfii.haveiplayedwith.store.StoreSchema.KEEP_RECENT;
import static me.wolfii.haveiplayedwith.store.StoreSchema.KEY;
import static me.wolfii.haveiplayedwith.store.StoreSchema.LAST_SEEN;
import static me.wolfii.haveiplayedwith.store.StoreSchema.LAST_VALID;
import static me.wolfii.haveiplayedwith.store.StoreSchema.MINUTES;
import static me.wolfii.haveiplayedwith.store.StoreSchema.NOTE;
import static me.wolfii.haveiplayedwith.store.StoreSchema.NOTE_TAKEN_AT;
import static me.wolfii.haveiplayedwith.store.StoreSchema.PLAYERS;
import static me.wolfii.haveiplayedwith.store.StoreSchema.PLAYER_UUID;
import static me.wolfii.haveiplayedwith.store.StoreSchema.PLAY_DAY;
import static me.wolfii.haveiplayedwith.store.StoreSchema.PLAY_DAYS;
import static me.wolfii.haveiplayedwith.store.StoreSchema.PLAY_SERVERS;
import static me.wolfii.haveiplayedwith.store.StoreSchema.PLAY_SESSIONS;
import static me.wolfii.haveiplayedwith.store.StoreSchema.PROFILES;
import static me.wolfii.haveiplayedwith.store.StoreSchema.RECENT_DAYS;
import static me.wolfii.haveiplayedwith.store.StoreSchema.RECENT_SESSIONS;
import static me.wolfii.haveiplayedwith.store.StoreSchema.SERVER_ID;
import static me.wolfii.haveiplayedwith.store.StoreSchema.SESSION_COUNT;
import static me.wolfii.haveiplayedwith.store.StoreSchema.TOTAL_MINUTES;
import static me.wolfii.haveiplayedwith.store.StoreSchema.USERNAME;
import static me.wolfii.haveiplayedwith.store.StoreSchema.USERNAME_HISTORY;
import static me.wolfii.haveiplayedwith.store.StoreSchema.USERNAME_LOWER;
import static org.dizitart.no2.collection.Document.createDocument;
import static org.dizitart.no2.filters.FluentFilter.where;

/**
 * Nitrite queries over the collections {@link StoreSchema} defines. All access
 * goes through {@link StoreWorker}. Minute ticks stay in {@link #pendingPlay}
 * until a flush, because rewriting four documents per online player every
 * minute leaves MVStore chunks that only a rewrite-compact reclaims.
 */
final class StoreDb implements AutoCloseable {
    private final Path file;
    private final StoreWorker worker;
    private Nitrite nitrite;
    private NitriteCollection players;
    private NitriteCollection history;
    private NitriteCollection playDays;
    private NitriteCollection sessions;
    private NitriteCollection playServers;
    private NitriteCollection profiles;
    /** Unwritten minute credits, one bucket per player, only touched on the db thread. */
    private final Map<UUID, PendingPlay> pendingPlay = new HashMap<>();

    private StoreDb(Path file, StoreWorker worker, Nitrite nitrite) {
        this.file = file;
        this.worker = worker;
        bind(nitrite);
    }

    static StoreDb open(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StoreDb db = new StoreDb(file, new StoreWorker(), openNitrite(file));
            db.worker.scheduleFlush(db::flushPending);
            db.worker.scheduleCompact(db::compactIfWorthwhile);
            return db;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
        }
    }

    private static Nitrite openNitrite(Path file) {
        StoreMv.cleanup(file);
        MVStoreModule storeModule = MVStoreModule.withConfig()
            .filePath(StoreMv.path(file))
            .compressHigh(true)
            .autoCommit(true)
            .autoCommitBufferSize(2048)
            .build();
        Nitrite nitrite = Nitrite.builder()
            .loadModule(storeModule)
            .openOrCreate();
        StoreSchema.create(nitrite);
        StoreMv.tune(nitrite);
        return nitrite;
    }

    private void bind(Nitrite nitrite) {
        this.nitrite = nitrite;
        this.players = nitrite.getCollection(PLAYERS);
        this.history = nitrite.getCollection(USERNAME_HISTORY);
        this.playDays = nitrite.getCollection(PLAY_DAYS);
        this.sessions = nitrite.getCollection(PLAY_SESSIONS);
        this.playServers = nitrite.getCollection(PLAY_SERVERS);
        this.profiles = nitrite.getCollection(PROFILES);
        worker.use(nitrite);
    }

    /**
     * Rewrites the store file onto the database thread when leftover MVStore
     * chunks would actually shrink it. Collections are rebound after the copy.
     */
    void compactIfWorthwhile() {
        if (!StoreMv.shouldCompact(file, nitrite)) {
            return;
        }
        flushPending();
        long before = StoreMv.size(file);
        try {
            nitrite.commit();
            nitrite.close();
        } catch (Exception e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith store before compact", e);
            try {
                bind(openNitrite(file));
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
        bind(openNitrite(file));
        long after = StoreMv.size(file);
        if (after < before) {
            ModLog.LOGGER.info("Compacted HaveIPlayedWith store from {} to {} bytes", before, after);
        }
    }

    boolean hasReclaimableSpace() {
        return StoreMv.shouldCompact(file, nitrite);
    }

    void commit() {
        nitrite.commit();
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
            if (nitrite != null && !nitrite.isClosed()) {
                nitrite.close();
            }
        });
    }

    boolean hasPlayer(UUID uuid) {
        return byKey(players, id(uuid)) != null;
    }

    void ensurePlayer(UUID uuid, String username) {
        if (hasPlayer(uuid)) {
            return;
        }
        insert(players, id(uuid), createDocument(CURRENT_USERNAME, username)
            .put(USERNAME_LOWER, lower(username))
            .put(NOTE, "")
            .put(NOTE_TAKEN_AT, 0L)
            .put(TOTAL_MINUTES, 0L)
            .put(SESSION_COUNT, 0)
            .put(DAYS_PLAYED, 0));
    }

    void setNote(UUID uuid, String note, long noteTakenAt) {
        update(players, id(uuid), doc -> {
            doc.put(NOTE, note);
            doc.put(NOTE_TAKEN_AT, noteTakenAt);
        });
    }

    void setCurrentUsername(UUID uuid, String username) {
        Document existing = byKey(players, id(uuid));
        if (existing == null || username.equals(text(existing, CURRENT_USERNAME))) {
            return;
        }
        existing.put(CURRENT_USERNAME, username);
        existing.put(USERNAME_LOWER, lower(username));
        players.update(existing);
    }

    void touchUsername(UUID uuid, String username, Instant seenAt) {
        long millis = seenAt.toEpochMilli();
        String key = key(id(uuid), lower(username));
        Document existing = byKey(history, key);
        if (existing != null && asLong(existing.get(LAST_SEEN)) >= millis) {
            return;
        }
        if (existing != null) {
            List<SeenName> names = listHistory(uuid);
            if (!names.isEmpty() && names.getFirst().username().equalsIgnoreCase(username)) {
                return;
            }
        }
        upsert(history, key, createDocument(PLAYER_UUID, id(uuid))
            .put(USERNAME_LOWER, lower(username))
            .put(USERNAME, username)
            .put(LAST_SEEN, millis));
    }

    Optional<String> previousSeenNameIfDifferent(UUID uuid, String username) {
        List<SeenName> names = listHistory(uuid);
        if (names.isEmpty() || names.getFirst().username().equalsIgnoreCase(username)) {
            return Optional.empty();
        }
        return Optional.of(names.getFirst().username());
    }

    List<PlayerSnapshot> findByName(String name) {
        Set<UUID> ids = new LinkedHashSet<>();
        String lower = lower(name);
        for (Document row : history.find(where(USERNAME_LOWER).eq(lower))) {
            ids.add(parseId(text(row, PLAYER_UUID)));
        }
        for (Document row : players.find(where(USERNAME_LOWER).eq(lower))) {
            ids.add(parseId(text(row, KEY)));
        }
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (UUID uuid : ids) {
            snapshot(uuid).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    Optional<PlayerSnapshot> snapshot(UUID uuid) {
        Document row = byKey(players, id(uuid));
        if (row == null) {
            return Optional.empty();
        }
        String noteText = text(row, NOTE);
        Optional<String> note = Optional.of(noteText).filter(value -> !value.isBlank());
        long noteTakenAt = asLong(row.get(NOTE_TAKEN_AT));
        Optional<Instant> takenAt = note.isEmpty() || noteTakenAt == 0L
            ? Optional.empty()
            : Optional.of(Instant.ofEpochMilli(noteTakenAt));
        return Optional.of(new PlayerSnapshot(
            uuid,
            text(row, CURRENT_USERNAME),
            note,
            takenAt,
            asLong(row.get(TOTAL_MINUTES)) + pendingMinutes(uuid),
            (int) asLong(row.get(SESSION_COUNT)),
            (int) asLong(row.get(DAYS_PLAYED)),
            lastPlayedBefore(row, LocalDate.now()),
            listHistory(uuid),
            mostPlayedServer(uuid)
        ));
    }

    Long sessionMinutes(UUID uuid, String sessionId) {
        Document row = byKey(sessions, key(id(uuid), sessionId));
        long stored = row == null ? 0L : asLong(row.get(MINUTES));
        PendingPlay pending = pendingPlay.get(uuid);
        if (pending != null && sessionId.equals(pending.sessionId)) {
            stored += pending.minutes;
        }
        return row == null && stored == 0L ? null : stored;
    }

    void addSessionMinute(UUID uuid, String sessionId) {
        PendingPlay pending = pendingPlay.get(uuid);
        if (pending != null && pending.minutes > 0 && pending.sessionId != null && !sessionId.equals(pending.sessionId)) {
            flushPending(uuid);
        }
        String key = key(id(uuid), sessionId);
        if (byKey(sessions, key) == null) {
            insert(sessions, key, createDocument(PLAYER_UUID, id(uuid)).put(MINUTES, 0L));
            rememberSession(uuid, sessionId);
        }
        pendingPlay.computeIfAbsent(uuid, id -> new PendingPlay()).sessionId = sessionId;
    }

    void addMinute(UUID uuid, LocalDate day, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId");
        }
        String iso = day.toString();
        String key = key(id(uuid), iso);
        if (byKey(playDays, key) == null) {
            insert(playDays, key, createDocument(PLAYER_UUID, id(uuid))
                .put(PLAY_DAY, iso)
                .put(MINUTES, 0L));
            rememberDay(uuid, iso);
        }
        ensureServer(uuid, serverId);
        creditPending(uuid, iso, serverId);
    }

    Optional<ProfileMapping> profileByUuid(UUID uuid) {
        return mapping(byKey(profiles, id(uuid)));
    }

    Optional<ProfileMapping> profileByName(String usernameLower) {
        ProfileMapping newest = null;
        for (Document row : profiles.find(where(USERNAME_LOWER).eq(lower(usernameLower)))) {
            ProfileMapping mapping = mapping(row).orElse(null);
            if (mapping == null) {
                continue;
            }
            if (newest == null || mapping.lastValid().isAfter(newest.lastValid())) {
                newest = mapping;
            }
        }
        return Optional.ofNullable(newest);
    }

    void putProfile(ProfileMapping mapping) {
        Instant lastValid = mapping.lastValid();
        if (mapping.uuid() != null) {
            String stored = mapping.username() == null ? "" : mapping.username();
            String lower = stored.isBlank() ? "" : lower(stored);
            if (!lower.isBlank()) {
                removeKey(profiles, nameMissKey(lower));
            }
            upsert(profiles, id(mapping.uuid()), createDocument(PLAYER_UUID, id(mapping.uuid()))
                .put(USERNAME, stored)
                .put(USERNAME_LOWER, lower)
                .put(LAST_VALID, lastValid.toEpochMilli()));
            return;
        }
        String lower = mapping.username() == null ? "" : lower(mapping.username());
        if (lower.isBlank()) {
            return;
        }
        upsert(profiles, nameMissKey(lower), createDocument(PLAYER_UUID, "")
            .put(USERNAME, "")
            .put(USERNAME_LOWER, lower)
            .put(LAST_VALID, lastValid.toEpochMilli()));
    }

    private static Optional<ProfileMapping> mapping(Document row) {
        if (row == null) {
            return Optional.empty();
        }
        String rawUuid = text(row, PLAYER_UUID);
        String rawName = text(row, USERNAME);
        Instant lastValid = Instant.ofEpochMilli(asLong(row.get(LAST_VALID)));
        if (rawUuid.isBlank()) {
            String lookedUp = text(row, USERNAME_LOWER);
            return Optional.of(new ProfileMapping(null, lookedUp.isBlank() ? null : lookedUp, lastValid));
        }
        return Optional.of(new ProfileMapping(
            parseId(rawUuid),
            rawName.isBlank() ? null : rawName,
            lastValid
        ));
    }

    private static String nameMissKey(String usernameLower) {
        return '\t' + usernameLower;
    }

    private static void removeKey(NitriteCollection collection, String key) {
        Document existing = byKey(collection, key);
        if (existing != null) {
            collection.remove(existing);
        }
    }

    private List<SeenName> listHistory(UUID uuid) {
        List<SeenName> names = new ArrayList<>();
        for (Document row : history.find(where(PLAYER_UUID).eq(id(uuid)))) {
            names.add(new SeenName(text(row, USERNAME), Instant.ofEpochMilli(asLong(row.get(LAST_SEEN)))));
        }
        names.sort(Comparator.comparing(SeenName::lastSeen).reversed());
        return names;
    }

    private Optional<LocalDate> lastPlayedBefore(Document player, LocalDate excluded) {
        LocalDate latest = null;
        String skip = excluded.toString();
        for (String day : stringList(player, RECENT_DAYS)) {
            if (day.isBlank() || day.equals(skip)) {
                continue;
            }
            LocalDate parsed = LocalDate.parse(day);
            if (latest == null || parsed.isAfter(latest)) {
                latest = parsed;
            }
        }
        return Optional.ofNullable(latest);
    }

    private Optional<ServerPlay> mostPlayedServer(UUID uuid) {
        PendingPlay pending = pendingPlay.get(uuid);
        ServerPlay best = null;
        for (Document row : playServers.find(where(PLAYER_UUID).eq(id(uuid)))) {
            String serverId = text(row, SERVER_ID);
            long minutes = asLong(row.get(MINUTES));
            if (pending != null && pending.minutes > 0 && serverId.equals(pending.serverId)) {
                minutes += pending.minutes;
            }
            ServerPlay server = new ServerPlay(serverId, minutes);
            if (best == null
                || server.minutes() > best.minutes()
                || (server.minutes() == best.minutes() && server.serverId().compareTo(best.serverId()) < 0)) {
                best = server;
            }
        }
        return Optional.ofNullable(best);
    }

    private void ensureServer(UUID uuid, String serverId) {
        String key = key(id(uuid), serverId);
        if (byKey(playServers, key) != null) {
            return;
        }
        insert(playServers, key, createDocument(PLAYER_UUID, id(uuid))
            .put(SERVER_ID, serverId)
            .put(MINUTES, 0L));
    }

    private void creditPending(UUID uuid, String dayIso, String serverId) {
        PendingPlay pending = pendingPlay.get(uuid);
        if (pending != null && pending.minutes > 0
            && ((pending.dayIso != null && !dayIso.equals(pending.dayIso))
                || (pending.serverId != null && !serverId.equals(pending.serverId)))) {
            String sessionId = pending.sessionId;
            flushPending(uuid);
            pending = pendingPlay.computeIfAbsent(uuid, id -> new PendingPlay());
            pending.sessionId = sessionId;
        }
        if (pending == null) {
            pending = pendingPlay.computeIfAbsent(uuid, id -> new PendingPlay());
        }
        pending.dayIso = dayIso;
        pending.serverId = serverId;
        pending.minutes++;
    }

    private long pendingMinutes(UUID uuid) {
        PendingPlay pending = pendingPlay.get(uuid);
        return pending == null ? 0L : pending.minutes;
    }

    void flushPending() {
        boolean wrote = false;
        for (UUID uuid : List.copyOf(pendingPlay.keySet())) {
            wrote |= flushPending(uuid);
        }
        if (wrote) {
            nitrite.commit();
        }
    }

    private boolean flushPending(UUID uuid) {
        PendingPlay pending = pendingPlay.remove(uuid);
        if (pending == null || pending.minutes <= 0) {
            return false;
        }
        long extra = pending.minutes;
        if (pending.sessionId != null && !pending.sessionId.isBlank()) {
            update(sessions, key(id(uuid), pending.sessionId),
                doc -> doc.put(MINUTES, asLong(doc.get(MINUTES)) + extra));
        }
        if (pending.serverId != null) {
            update(playServers, key(id(uuid), pending.serverId),
                doc -> doc.put(MINUTES, asLong(doc.get(MINUTES)) + extra));
        }
        update(players, id(uuid), doc -> doc.put(TOTAL_MINUTES, asLong(doc.get(TOTAL_MINUTES)) + extra));
        return true;
    }

    private void rememberSession(UUID uuid, String sessionId) {
        update(players, id(uuid), doc -> {
            doc.put(SESSION_COUNT, (int) asLong(doc.get(SESSION_COUNT)) + 1);
            List<String> values = stringList(doc, RECENT_SESSIONS);
            if (!values.contains(sessionId)) {
                values.add(sessionId);
                while (values.size() > KEEP_RECENT) {
                    removeKey(sessions, key(id(uuid), values.removeFirst()));
                }
                doc.put(RECENT_SESSIONS, values);
            }
        });
    }

    private void rememberDay(UUID uuid, String newest) {
        update(players, id(uuid), doc -> {
            doc.put(DAYS_PLAYED, (int) asLong(doc.get(DAYS_PLAYED)) + 1);
            List<String> days = stringList(doc, RECENT_DAYS);
            if (!days.contains(newest)) {
                days.add(newest);
            }
            days.sort(null);
            while (days.size() > KEEP_RECENT) {
                removeKey(playDays, key(id(uuid), days.removeFirst()));
            }
            doc.put(RECENT_DAYS, days);
        });
    }

    private static List<String> stringList(Document document, String field) {
        Object value = document.get(field);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static Document byKey(NitriteCollection collection, String key) {
        return collection.find(where(KEY).eq(key)).firstOrNull();
    }

    private static void insert(NitriteCollection collection, String key, Document fields) {
        fields.put(KEY, key);
        collection.insert(fields);
    }

    private static boolean update(NitriteCollection collection, String key, Consumer<Document> mutator) {
        Document existing = byKey(collection, key);
        if (existing == null) {
            return false;
        }
        mutator.accept(existing);
        collection.update(existing);
        return true;
    }

    private static void upsert(NitriteCollection collection, String key, Document fields) {
        if (update(collection, key, existing -> {
            for (String field : fields.getFields()) {
                existing.put(field, fields.get(field));
            }
        })) {
            return;
        }
        insert(collection, key, fields);
    }

    private static String id(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static String key(String left, String right) {
        return left + '\t' + right;
    }

    private static UUID parseId(String compact) {
        if (compact.indexOf('-') >= 0) {
            return UUID.fromString(compact);
        }
        return UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
            + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20));
    }

    private static String lower(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static String text(Document document, String field) {
        Object value = document.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static final class PendingPlay {
        private String sessionId;
        private String dayIso;
        private String serverId;
        private long minutes;
    }
}
