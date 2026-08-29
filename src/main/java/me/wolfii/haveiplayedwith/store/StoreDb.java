package me.wolfii.haveiplayedwith.store;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static me.wolfii.haveiplayedwith.store.StoreSchema.CURRENT_USERNAME;
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
 * goes through {@link StoreWorker}. Writes stay in MVStore's memory until its
 * auto-commit thread flushes (about a second of idle, H2's default).
 */
final class StoreDb implements AutoCloseable {
    private final StoreWorker worker;
    private final NitriteCollection players;
    private final NitriteCollection history;
    private final NitriteCollection playDays;
    private final NitriteCollection sessions;
    private final NitriteCollection playServers;
    private final NitriteCollection profiles;

    private StoreDb(StoreWorker worker, Nitrite nitrite) {
        this.worker = worker;
        this.players = nitrite.getCollection(PLAYERS);
        this.history = nitrite.getCollection(USERNAME_HISTORY);
        this.playDays = nitrite.getCollection(PLAY_DAYS);
        this.sessions = nitrite.getCollection(PLAY_SESSIONS);
        this.playServers = nitrite.getCollection(PLAY_SERVERS);
        this.profiles = nitrite.getCollection(PROFILES);
    }

    static StoreDb open(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MVStoreModule storeModule = MVStoreModule.withConfig()
                .filePath(file.toAbsolutePath().toString())
                .compress(true)
                .autoCommit(true)
                .build();
            Nitrite nitrite = Nitrite.builder()
                .loadModule(storeModule)
                .openOrCreate();
            StoreSchema.create(nitrite);
            return new StoreDb(new StoreWorker(nitrite), nitrite);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
        }
    }

    <T> T call(Callable<T> task) {
        return worker.call(task);
    }

    void run(StoreWork task) {
        worker.run(task);
    }

    @Override
    public void close() {
        worker.close();
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
            .put(SESSION_COUNT, 0));
    }

    void setNote(UUID uuid, String note, long noteTakenAt) {
        update(players, id(uuid), doc -> {
            doc.put(NOTE, note);
            doc.put(NOTE_TAKEN_AT, noteTakenAt);
        });
    }

    void setCurrentUsername(UUID uuid, String username) {
        update(players, id(uuid), doc -> {
            doc.put(CURRENT_USERNAME, username);
            doc.put(USERNAME_LOWER, lower(username));
        });
    }

    void touchUsername(UUID uuid, String username, Instant seenAt) {
        long millis = seenAt.toEpochMilli();
        String key = key(id(uuid), lower(username));
        Document existing = byKey(history, key);
        if (existing != null && asLong(existing.get(LAST_SEEN)) >= millis) {
            return;
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
            ids.add(UUID.fromString(text(row, PLAYER_UUID)));
        }
        for (Document row : players.find(where(USERNAME_LOWER).eq(lower))) {
            ids.add(UUID.fromString(text(row, KEY)));
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
            asLong(row.get(TOTAL_MINUTES)),
            (int) asLong(row.get(SESSION_COUNT)),
            countPlayDays(uuid),
            lastPlayedBefore(uuid, LocalDate.now()),
            listHistory(uuid),
            mostPlayedServer(uuid)
        ));
    }

    Long sessionMinutes(UUID uuid, String sessionId) {
        Document row = byKey(sessions, key(id(uuid), sessionId));
        return row == null ? null : asLong(row.get(MINUTES));
    }

    void addSessionMinute(UUID uuid, String sessionId) {
        String key = key(id(uuid), sessionId);
        if (update(sessions, key, doc -> doc.put(MINUTES, asLong(doc.get(MINUTES)) + 1))) {
            return;
        }
        insert(sessions, key, createDocument(MINUTES, 1L));
        bumpSessionCount(uuid);
    }

    void addMinute(UUID uuid, LocalDate day, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId");
        }
        String key = key(id(uuid), day.toString());
        if (!update(playDays, key, doc -> doc.put(MINUTES, asLong(doc.get(MINUTES)) + 1))) {
            insert(playDays, key, createDocument(PLAYER_UUID, id(uuid))
                .put(PLAY_DAY, day.toString())
                .put(MINUTES, 1L));
        }
        addServerMinute(uuid, serverId);
        update(players, id(uuid), doc -> doc.put(TOTAL_MINUTES, asLong(doc.get(TOTAL_MINUTES)) + 1));
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
            UUID.fromString(rawUuid),
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

    private int countPlayDays(UUID uuid) {
        return (int) playDays.find(where(PLAYER_UUID).eq(id(uuid))).size();
    }

    private Optional<LocalDate> lastPlayedBefore(UUID uuid, LocalDate excluded) {
        LocalDate latest = null;
        String skip = excluded.toString();
        for (Document row : playDays.find(where(PLAYER_UUID).eq(id(uuid)))) {
            String day = text(row, PLAY_DAY);
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
        ServerPlay best = null;
        for (Document row : playServers.find(where(PLAYER_UUID).eq(id(uuid)))) {
            ServerPlay server = new ServerPlay(text(row, SERVER_ID), asLong(row.get(MINUTES)));
            if (best == null
                || server.minutes() > best.minutes()
                || (server.minutes() == best.minutes() && server.serverId().compareTo(best.serverId()) < 0)) {
                best = server;
            }
        }
        return Optional.ofNullable(best);
    }

    private void addServerMinute(UUID uuid, String serverId) {
        String key = key(id(uuid), serverId);
        if (update(playServers, key, doc -> doc.put(MINUTES, asLong(doc.get(MINUTES)) + 1))) {
            return;
        }
        insert(playServers, key, createDocument(PLAYER_UUID, id(uuid))
            .put(SERVER_ID, serverId)
            .put(MINUTES, 1L));
    }

    private void bumpSessionCount(UUID uuid) {
        update(players, id(uuid), doc -> doc.put(SESSION_COUNT, (int) asLong(doc.get(SESSION_COUNT)) + 1));
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
        return uuid.toString();
    }

    private static String key(String left, String right) {
        return left + '\t' + right;
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
}
