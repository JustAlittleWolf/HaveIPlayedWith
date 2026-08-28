package me.wolfii.haveiplayedwith.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.haveiplayedwith.http.JsonHttp;
import me.wolfii.haveiplayedwith.http.RateLimiter;
import me.wolfii.haveiplayedwith.store.MojangNameCache;
import me.wolfii.haveiplayedwith.store.MojangProfileStore;
import me.wolfii.haveiplayedwith.store.MojangUuidCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
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
 * Wrapper for the Mojang Minecraft profile APIs on {@code api.mojang.com}.
 * Lookups are cached; network calls are limited to 25 / 10s.
 */
public final class MojangProfileApi {
    public static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
    private static final String UUID_LOOKUP = "https://api.mojang.com/minecraft/profile/lookup/";
    private static final String NAME_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/";
    private final MojangProfileStore store;
    private final RateLimiter limiter = new RateLimiter(25, 10, TimeUnit.SECONDS);
    private final ConcurrentHashMap<UUID, MojangUuidCache> uuidMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MojangNameCache> nameMemory = new ConcurrentHashMap<>();

    public MojangProfileApi(MojangProfileStore store) {
        this.store = store;
    }

    private static Optional<MojangProfile> profileOf(MojangNameCache cache) {
        if (cache.uuid() == null || cache.username() == null || cache.username().isBlank()) {
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
        stored.ifPresent(cache -> uuidMemory.put(uuid, cache));
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
        if (cachedName != null && !cachedName.isBlank() && cachedName.equalsIgnoreCase(observedName)) {
            return false;
        }
        if (cachedName != null && !cachedName.isBlank()) {
            return true;
        }
        return isStale(cache.get());
    }

    public Optional<MojangProfile> lookupUuid(UUID uuid) {
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
            nameMemory.put(key, stored.get());
            return profileOf(stored.get());
        }
        NameAnswer answer = fetchName(username);
        if (answer.definitive()) {
            Instant now = Instant.now();
            MojangNameCache cache = answer.profile()
                .map(profile -> new MojangNameCache(profile.uuid(), profile.username(), now))
                .orElse(new MojangNameCache(null, "", now));
            nameMemory.put(key, cache);
            store.putName(key, cache);
            answer.profile().ifPresent(profile -> storeUuid(profile.uuid(), profile.username()));
        }
        return answer.profile();
    }

    private Optional<MojangProfile> fetchUuid(UUID uuid) {
        try {
            limiter.acquire();
            HttpResponse<String> response = JsonHttp.get(UUID_LOOKUP + uuid);
            int status = response.statusCode();
            if (status == 204 || status == 404) {
                storeUuid(uuid, "");
                return Optional.empty();
            }
            if (status == 429) {
                LOGGER.debug("Mojang UUID lookup rate limited for {}", uuid);
                return Optional.empty();
            }
            if (status / 100 != 2) {
                LOGGER.debug("Mojang UUID lookup {} returned {}", uuid, status);
                return Optional.empty();
            }
            String name = readName(response.body());
            storeUuid(uuid, name == null ? "" : name);
            return name == null || name.isBlank() ? Optional.empty() : Optional.of(new MojangProfile(uuid, name));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.debug("Mojang UUID lookup failed for {}", uuid, e);
            return Optional.empty();
        }
    }

    private NameAnswer fetchName(String username) {
        try {
            limiter.acquire();
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
            HttpResponse<String> response = JsonHttp.get(NAME_LOOKUP + encoded);
            int status = response.statusCode();
            if (status == 204 || status == 404) {
                return new NameAnswer(Optional.empty(), true);
            }
            if (status / 100 != 2) {
                LOGGER.debug("Mojang name lookup {} returned {}", username, status);
                return NameAnswer.unknown();
            }
            return new NameAnswer(readProfile(response.body()), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NameAnswer.unknown();
        } catch (Exception e) {
            LOGGER.debug("Mojang name lookup failed for {}", username, e);
            return NameAnswer.unknown();
        }
    }

    private void storeUuid(UUID uuid, String username) {
        MojangUuidCache cache = new MojangUuidCache(username, Instant.now());
        uuidMemory.put(uuid, cache);
        store.putUuid(uuid, username, cache.fetchedAt());
    }

    /** A name lookup result, plus whether the API actually answered so it may be remembered. */
    private record NameAnswer(Optional<MojangProfile> profile, boolean definitive) {
        static NameAnswer unknown() {
            return new NameAnswer(Optional.empty(), false);
        }
    }
}
