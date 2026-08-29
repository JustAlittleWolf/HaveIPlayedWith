package me.wolfii.haveiplayedwith.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.store.ProfileCache;
import me.wolfii.haveiplayedwith.store.ProfileMapping;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Looks up Minecraft profiles on {@code api.mojang.com}. Lookups are cached;
 * network calls are limited to 25 / 10s.
 */
public final class ProfileApi {
    public static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final String UUID_LOOKUP = "https://api.mojang.com/minecraft/profile/lookup/";
    private static final String NAME_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final ProfileCache store;
    private final RateLimiter limiter = new RateLimiter(190, 60, TimeUnit.SECONDS);
    private final ConcurrentHashMap<UUID, ProfileMapping> uuidMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProfileMapping> nameMemory = new ConcurrentHashMap<>();

    public ProfileApi(ProfileCache store) {
        this.store = store;
    }

    private static Optional<Profile> profileOf(ProfileMapping mapping) {
        if (!mapping.resolved()) {
            return Optional.empty();
        }
        return Optional.of(new Profile(mapping.uuid(), mapping.username()));
    }

    private static String readName(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        return json.has("name") ? json.get("name").getAsString() : null;
    }

    private static Optional<Profile> readProfile(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (!json.has("id") || !json.has("name")) {
            return Optional.empty();
        }
        return Optional.of(new Profile(parseUuid(json.get("id").getAsString()), json.get("name").getAsString()));
    }

    public static UUID parseUuid(String id) {
        String dashed = id.contains("-") ? id : id.replaceFirst(
            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
            "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(dashed);
    }

    public Optional<ProfileMapping> cached(UUID uuid) {
        ProfileMapping memory = uuidMemory.get(uuid);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<ProfileMapping> stored = store.byUuid(uuid);
        stored.ifPresent(this::remember);
        return stored;
    }

    private boolean isStale(ProfileMapping mapping) {
        return Instant.now().minus(STALE_AFTER).isAfter(mapping.lastValid());
    }

    public boolean needsFetch(UUID uuid, String observedName) {
        Optional<ProfileMapping> cache = cached(uuid);
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
     * True when this UUID is already confirmed to belong to {@code observedName}.
     * Cached misses (empty username after 204/404) are not a match, so nearby NPCs
     * with fake UUIDs are not credited as players.
     */
    public boolean matchesCachedName(UUID uuid, String observedName) {
        Optional<ProfileMapping> cache = cached(uuid);
        return cache.isPresent()
            && cache.get().resolved()
            && cache.get().username().equalsIgnoreCase(observedName);
    }

    public Optional<Profile> lookupUuid(UUID uuid) {
        Optional<ProfileMapping> cache = cached(uuid);
        if (cache.isPresent() && !isStale(cache.get()) && !cache.get().resolved()) {
            return Optional.empty();
        }
        return fetchUuid(uuid);
    }

    public Optional<Profile> lookupName(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        Optional<ProfileMapping> cache = cachedName(key);
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

    private Optional<ProfileMapping> cachedName(String key) {
        ProfileMapping memory = nameMemory.get(key);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<ProfileMapping> stored = store.byName(key);
        stored.ifPresent(this::remember);
        return stored;
    }

    private void remember(ProfileMapping mapping) {
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
        ProfileMapping mapping = new ProfileMapping(null, key, Instant.now());
        remember(mapping);
        store.put(mapping);
    }

    private Optional<Profile> fetchUuid(UUID uuid) {
        try {
            return switch (get(UUID_LOOKUP + uuid)) {
                case Answer.Body body -> {
                    String name = readName(body.json());
                    storeUuid(uuid, name == null ? "" : name);
                    yield name != null && !name.isBlank() ? Optional.of(new Profile(uuid, name)) : Optional.empty();
                }
                case Answer.Missing ignored -> {
                    storeUuid(uuid, "");
                    yield Optional.empty();
                }
                case Answer.Unavailable ignored -> Optional.empty();
            };
        } catch (RuntimeException e) {
            ModLog.LOGGER.debug("UUID lookup failed for {}", uuid, e);
            return Optional.empty();
        }
    }

    private NameAnswer fetchName(String username) {
        try {
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
            return switch (get(NAME_LOOKUP + encoded)) {
                case Answer.Body body -> new NameAnswer(readProfile(body.json()), true);
                case Answer.Missing ignored -> new NameAnswer(Optional.empty(), true);
                case Answer.Unavailable ignored -> NameAnswer.unknown();
            };
        } catch (RuntimeException e) {
            ModLog.LOGGER.debug("Name lookup failed for {}", username, e);
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
        ProfileMapping existing = nameMemory.get(key);
        if (existing != null && uuid.equals(existing.uuid())) {
            uuidMemory.putIfAbsent(uuid, existing);
            return;
        }
        ProfileMapping mapping = new ProfileMapping(uuid, username, Instant.now());
        remember(mapping);
        store.put(mapping);
    }

    private void storeUuid(UUID uuid, String username) {
        if (username == null || username.isBlank()) {
            ProfileMapping mapping = new ProfileMapping(uuid, "", Instant.now());
            remember(mapping);
            store.put(mapping);
            return;
        }
        rememberCurrent(uuid, username);
    }

    private Answer get(String url) {
        try {
            limiter.acquire();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "HaveIPlayedWith/1.0 (https://github.com/JustAlittleWolf/HaveIPlayedWith)")
                .GET()
                .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 204 || status == 404) {
                return Answer.MISSING;
            }
            if (status / 100 != 2) {
                ModLog.LOGGER.debug("Profile lookup returned {} for {}", status, url);
                return Answer.UNAVAILABLE;
            }
            return new Answer.Body(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.UNAVAILABLE;
        } catch (Exception e) {
            ModLog.LOGGER.debug("Profile request failed: {}", url, e);
            return Answer.UNAVAILABLE;
        }
    }

    private sealed interface Answer {
        Missing MISSING = new Missing();
        Unavailable UNAVAILABLE = new Unavailable();

        record Body(String json) implements Answer {
        }

        record Missing() implements Answer {
        }

        record Unavailable() implements Answer {
        }
    }

    /** A name lookup result, plus whether the API actually answered so it may be remembered. */
    private record NameAnswer(Optional<Profile> profile, boolean definitive) {
        static NameAnswer unknown() {
            return new NameAnswer(Optional.empty(), false);
        }
    }
}
