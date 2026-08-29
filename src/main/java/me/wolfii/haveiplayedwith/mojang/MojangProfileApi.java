package me.wolfii.haveiplayedwith.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.http.JsonAnswer;
import me.wolfii.haveiplayedwith.http.JsonApi;
import me.wolfii.haveiplayedwith.http.RateLimiter;
import me.wolfii.haveiplayedwith.store.MojangMapping;
import me.wolfii.haveiplayedwith.store.MojangProfileStore;

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
    private final ConcurrentHashMap<UUID, MojangMapping> uuidMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MojangMapping> nameMemory = new ConcurrentHashMap<>();

    public MojangProfileApi(MojangProfileStore store) {
        this.store = store;
    }

    private static Optional<MojangProfile> profileOf(MojangMapping mapping) {
        if (!mapping.resolved()) {
            return Optional.empty();
        }
        return Optional.of(new MojangProfile(mapping.uuid(), mapping.username()));
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

    public Optional<MojangMapping> cached(UUID uuid) {
        MojangMapping memory = uuidMemory.get(uuid);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<MojangMapping> stored = store.byUuid(uuid);
        stored.ifPresent(this::remember);
        return stored;
    }

    public boolean isStale(MojangMapping mapping) {
        return Instant.now().minus(STALE_AFTER).isAfter(mapping.lastValid());
    }

    public boolean needsFetch(UUID uuid, String observedName) {
        Optional<MojangMapping> cache = cached(uuid);
        if (cache.isEmpty()) {
            return true;
        }
        if (cache.get().resolved() && cache.get().username().equalsIgnoreCase(observedName)) {
            return false;
        }
        if (cache.get().resolved()) {
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
        Optional<MojangMapping> cache = cached(uuid);
        return cache.isPresent()
            && cache.get().resolved()
            && cache.get().username().equalsIgnoreCase(observedName);
    }

    public Optional<MojangProfile> lookupUuid(UUID uuid) {
        Optional<MojangMapping> cache = cached(uuid);
        if (cache.isPresent() && !isStale(cache.get()) && !cache.get().resolved()) {
            return Optional.empty();
        }
        return fetchUuid(uuid);
    }

    public Optional<MojangProfile> lookupName(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        Optional<MojangMapping> cache = cachedName(key);
        if (cache.isPresent() && !isStale(cache.get())) {
            return profileOf(cache.get());
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

    private Optional<MojangMapping> cachedName(String key) {
        MojangMapping memory = nameMemory.get(key);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<MojangMapping> stored = store.byName(key);
        stored.ifPresent(this::remember);
        return stored;
    }

    private void remember(MojangMapping mapping) {
        if (mapping.uuid() != null) {
            uuidMemory.put(mapping.uuid(), mapping);
        }
        if (mapping.resolved()) {
            nameMemory.put(mapping.username().toLowerCase(Locale.ROOT), mapping);
            return;
        }
        if (mapping.uuid() == null && mapping.username() != null && !mapping.username().isBlank()) {
            nameMemory.put(mapping.username().toLowerCase(Locale.ROOT), mapping);
        }
    }

    /** Remembers that no account holds this name, so it is not looked up again until stale. */
    private void rememberMissingName(String key) {
        MojangMapping mapping = new MojangMapping(null, key, Instant.now());
        remember(mapping);
        store.put(mapping);
    }

    private Optional<MojangProfile> fetchUuid(UUID uuid) {
        try {
            return switch (api.get(UUID_LOOKUP + uuid)) {
                case JsonAnswer.Body body -> {
                    String name = readName(body.json());
                    storeUuid(uuid, name == null ? "" : name);
                    yield name != null && !name.isBlank() ? Optional.of(new MojangProfile(uuid, name)) : Optional.empty();
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
        try {
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
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
     * Remember who currently owns this name so later live play can skip the API.
     */
    public void rememberCurrent(UUID uuid, String username) {
        if (uuid == null || username == null || username.isBlank()) {
            return;
        }
        String key = username.toLowerCase(Locale.ROOT);
        MojangMapping existing = nameMemory.get(key);
        if (existing != null && uuid.equals(existing.uuid())) {
            uuidMemory.putIfAbsent(uuid, existing);
            return;
        }
        MojangMapping mapping = new MojangMapping(uuid, username, Instant.now());
        remember(mapping);
        store.putCurrent(uuid, username, mapping.lastValid());
    }

    private void storeUuid(UUID uuid, String username) {
        if (username == null || username.isBlank()) {
            MojangMapping mapping = new MojangMapping(uuid, "", Instant.now());
            remember(mapping);
            store.put(mapping);
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
