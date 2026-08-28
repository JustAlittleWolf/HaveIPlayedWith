package me.wolfii.haveiplayedwith.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.http.JsonAnswer;
import me.wolfii.haveiplayedwith.http.JsonApi;
import me.wolfii.haveiplayedwith.http.RateLimiter;
import me.wolfii.haveiplayedwith.store.MojangNameCache;
import me.wolfii.haveiplayedwith.store.MojangProfileStore;
import me.wolfii.haveiplayedwith.store.MojangUuidCache;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper for the Mojang Minecraft profile APIs on {@code api.mojang.com}.
 * Lookups are cached; network calls are limited to 25 / 10s.
 */
public final class MojangProfileApi {
    public static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final String UUID_LOOKUP = "https://api.mojang.com/minecraft/profile/lookup/";
    private static final String NAME_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/";
    private final MojangProfileStore store;
    private final JsonApi api = new JsonApi("Mojang", new RateLimiter(25, 10, TimeUnit.SECONDS));
    private final ConcurrentHashMap<UUID, MojangUuidCache> uuidMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MojangNameCache> nameMemory = new ConcurrentHashMap<>();

    public MojangProfileApi(MojangProfileStore store) {
        this.store = store;
    }

    /** False for a cached miss, where an empty username records that nobody holds the name. */
    private static boolean resolved(String username) {
        return username != null && !username.isBlank();
    }

    private static Optional<MojangProfile> profileOf(MojangNameCache cache) {
        if (cache.uuid() == null || !resolved(cache.username())) {
            return Optional.empty();
        }
        return Optional.of(new MojangProfile(cache.uuid(), cache.username()));
    }

    private static String readName(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        return json.has("name") ? json.get("name").getAsString() : null;
    }

    private static Optional<MojangProfile> readProfile(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (!json.has("id") || !json.has("name")) {
            return Optional.empty();
        }
        return Optional.of(new MojangProfile(parseUuid(json.get("id").getAsString()), json.get("name").getAsString()));
    }

    public static UUID parseUuid(String id) {
        String dashed = id.contains("-") ? id : id.replaceFirst(
            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
            "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(dashed);
    }

    public Optional<MojangUuidCache> cached(UUID uuid) {
        MojangUuidCache memory = uuidMemory.get(uuid);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<MojangUuidCache> stored = store.byUuid(uuid);
        stored.ifPresent(cache -> {
            uuidMemory.put(uuid, cache);
            if (resolved(cache.username())) {
                nameMemory.putIfAbsent(
                    cache.username().toLowerCase(Locale.ROOT),
                    new MojangNameCache(uuid, cache.username(), cache.fetchedAt())
                );
            }
        });
        return stored;
    }

    public boolean isStale(MojangUuidCache cache) {
        return Instant.now().minus(STALE_AFTER).isAfter(cache.fetchedAt());
    }

    public boolean needsFetch(UUID uuid, String observedName) {
        Optional<MojangUuidCache> cache = cached(uuid);
        if (cache.isEmpty()) {
            return true;
        }
        String cachedName = cache.get().username();
        if (resolved(cachedName) && cachedName.equalsIgnoreCase(observedName)) {
            return false;
        }
        if (resolved(cachedName)) {
            return true;
        }
        return isStale(cache.get());
    }

    /**
     * True when Mojang already confirmed this UUID belongs to {@code observedName}.
     * Cached misses (empty username after 204/404) are not a match, so nearby NPCs
     * with fake UUIDs are not credited as players.
     */
    public boolean matchesCachedName(UUID uuid, String observedName) {
        Optional<MojangUuidCache> cache = cached(uuid);
        if (cache.isEmpty()) {
            return false;
        }
        String cachedName = cache.get().username();
        return resolved(cachedName) && cachedName.equalsIgnoreCase(observedName);
    }

    public Optional<MojangProfile> lookupUuid(UUID uuid) {
        Optional<MojangUuidCache> cache = cached(uuid);
        if (cache.isPresent() && !isStale(cache.get()) && !resolved(cache.get().username())) {
            return Optional.empty();
        }
        return fetchUuid(uuid);
    }

    public Optional<MojangProfile> lookupName(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        MojangNameCache memory = nameMemory.get(key);
        if (memory != null) {
            return profileOf(memory);
        }
        Optional<MojangNameCache> stored = store.byName(key);
        if (stored.isPresent()) {
            return profileOf(rememberStored(key, stored.get()));
        }
        NameAnswer answer = fetchName(username);
        if (answer.definitive()) {
            answer.profile().ifPresentOrElse(
                profile -> rememberCurrent(profile.uuid(), profile.username()),
                () -> rememberMissingName(key)
            );
        }
        return answer.profile();
    }

    /** Loads a stored mapping into memory, both directions when it resolved to an account. */
    private MojangNameCache rememberStored(String key, MojangNameCache cache) {
        nameMemory.put(key, cache);
        if (cache.uuid() != null && resolved(cache.username())) {
            uuidMemory.putIfAbsent(cache.uuid(), new MojangUuidCache(cache.username(), cache.fetchedAt()));
        }
        return cache;
    }

    /** Remembers that no account holds this name, so it is not looked up again. */
    private void rememberMissingName(String key) {
        MojangNameCache cache = new MojangNameCache(null, "", Instant.now());
        nameMemory.put(key, cache);
        store.putName(key, cache);
    }

    private Optional<MojangProfile> fetchUuid(UUID uuid) {
        try {
            return switch (api.get(UUID_LOOKUP + uuid)) {
                case JsonAnswer.Body body -> {
                    String name = readName(body.json());
                    storeUuid(uuid, name == null ? "" : name);
                    yield resolved(name) ? Optional.of(new MojangProfile(uuid, name)) : Optional.empty();
                }
                case JsonAnswer.Missing ignored -> {
                    storeUuid(uuid, "");
                    yield Optional.empty();
                }
                case JsonAnswer.Unavailable ignored -> Optional.empty();
            };
        } catch (RuntimeException e) {
            ModLog.LOGGER.debug("Mojang UUID lookup failed for {}", uuid, e);
            return Optional.empty();
        }
    }

    private NameAnswer fetchName(String username) {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        try {
            return switch (api.get(NAME_LOOKUP + encoded)) {
                case JsonAnswer.Body body -> new NameAnswer(readProfile(body.json()), true);
                case JsonAnswer.Missing ignored -> new NameAnswer(Optional.empty(), true);
                case JsonAnswer.Unavailable ignored -> NameAnswer.unknown();
            };
        } catch (RuntimeException e) {
            ModLog.LOGGER.debug("Mojang name lookup failed for {}", username, e);
            return NameAnswer.unknown();
        }
    }

    /**
     * Remember who currently owns this name. Used after a Mojang fetch and after Crafty
     * resolves a player during import, so later live play can skip the API.
     */
    public void rememberCurrent(UUID uuid, String username) {
        if (uuid == null || username == null || username.isBlank()) {
            return;
        }
        String key = username.toLowerCase(Locale.ROOT);
        MojangNameCache existing = nameMemory.get(key);
        if (existing != null && uuid.equals(existing.uuid())) {
            uuidMemory.putIfAbsent(uuid, new MojangUuidCache(username, existing.fetchedAt()));
            return;
        }
        Instant now = Instant.now();
        uuidMemory.put(uuid, new MojangUuidCache(username, now));
        nameMemory.put(key, new MojangNameCache(uuid, username, now));
        store.putCurrent(uuid, username, now);
    }

    private void storeUuid(UUID uuid, String username) {
        if (username == null || username.isBlank()) {
            MojangUuidCache cache = new MojangUuidCache("", Instant.now());
            uuidMemory.put(uuid, cache);
            store.putUuid(uuid, "", cache.fetchedAt());
            return;
        }
        rememberCurrent(uuid, username);
    }

    /** A name lookup result, plus whether the API actually answered so it may be remembered. */
    private record NameAnswer(Optional<MojangProfile> profile, boolean definitive) {
        static NameAnswer unknown() {
            return new NameAnswer(Optional.empty(), false);
        }
    }
}
