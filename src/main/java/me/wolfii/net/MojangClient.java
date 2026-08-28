package me.wolfii.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.db.PlayerDatabase;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mojang profile lookups with a persistent cache. Network calls are limited to 25 / 10s.
 */
public final class MojangClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
	public static final Duration STALE_AFTER = Duration.ofHours(24);
	private static final String UUID_LOOKUP = "https://api.mojang.com/minecraft/profile/lookup/";
	private static final String NAME_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/";

	public record Profile(UUID uuid, String username) {
	}

	private final PlayerDatabase database;
	private final RateLimiter limiter = new RateLimiter(25, 10, TimeUnit.SECONDS);
	private final ConcurrentHashMap<UUID, PlayerDatabase.MojangCache> uuidMemory = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, PlayerDatabase.MojangNameCache> nameMemory = new ConcurrentHashMap<>();

	public MojangClient(PlayerDatabase database) {
		this.database = database;
	}

	public Optional<PlayerDatabase.MojangCache> cached(UUID uuid) {
		PlayerDatabase.MojangCache memory = uuidMemory.get(uuid);
		if (memory != null) {
			return Optional.of(memory);
		}
		Optional<PlayerDatabase.MojangCache> stored = database.mojangCache(uuid);
		stored.ifPresent(cache -> uuidMemory.put(uuid, cache));
		return stored;
	}

	public boolean isStale(PlayerDatabase.MojangCache cache) {
		return Instant.now().minus(STALE_AFTER).isAfter(cache.fetchedAt());
	}

	public boolean isFreshMismatch(UUID uuid, String observedName) {
		Optional<PlayerDatabase.MojangCache> cache = cached(uuid);
		if (cache.isEmpty() || cache.get().username() == null) {
			return false;
		}
		if (cache.get().username().equalsIgnoreCase(observedName)) {
			return false;
		}
		return !isStale(cache.get());
	}

	public boolean needsFetch(UUID uuid, String observedName) {
		Optional<PlayerDatabase.MojangCache> cache = cached(uuid);
		if (cache.isEmpty()) {
			return true;
		}
		if (cache.get().username() != null && cache.get().username().equalsIgnoreCase(observedName)) {
			return false;
		}
		return isStale(cache.get());
	}

	public Optional<Profile> lookupUuid(UUID uuid) {
		return fetchUuid(uuid);
	}

	public Optional<Profile> lookupName(String username) {
		String key = username.toLowerCase(Locale.ROOT);
		PlayerDatabase.MojangNameCache memory = nameMemory.get(key);
		if (memory != null) {
			return profileOf(memory);
		}
		Optional<PlayerDatabase.MojangNameCache> stored = database.mojangNameCache(key);
		if (stored.isPresent()) {
			nameMemory.put(key, stored.get());
			return profileOf(stored.get());
		}
		NameAnswer answer = fetchName(username);
		if (answer.definitive()) {
			Instant now = Instant.now();
			PlayerDatabase.MojangNameCache cache = answer.profile()
				.map(profile -> new PlayerDatabase.MojangNameCache(profile.uuid(), profile.username(), now))
				.orElse(new PlayerDatabase.MojangNameCache(null, null, now));
			nameMemory.put(key, cache);
			database.putMojangNameCache(key, cache);
			answer.profile().ifPresent(profile -> store(profile.uuid(), profile.username()));
		}
		return answer.profile();
	}

	private Optional<Profile> fetchUuid(UUID uuid) {
		try {
			limiter.acquire();
			HttpResponse<String> response = HttpJson.get(UUID_LOOKUP + uuid);
			int status = response.statusCode();
			if (status == 204 || status == 404) {
				store(uuid, null);
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
			store(uuid, name);
			return name == null ? Optional.empty() : Optional.of(new Profile(uuid, name));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			LOGGER.debug("Mojang UUID lookup failed for {}", uuid, e);
			return Optional.empty();
		}
	}

	/** A name lookup result, plus whether the API actually answered so it may be remembered. */
	private record NameAnswer(Optional<Profile> profile, boolean definitive) {
		static NameAnswer unknown() {
			return new NameAnswer(Optional.empty(), false);
		}
	}

	private NameAnswer fetchName(String username) {
		try {
			limiter.acquire();
			String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
			HttpResponse<String> response = HttpJson.get(NAME_LOOKUP + encoded);
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

	private void store(UUID uuid, String username) {
		PlayerDatabase.MojangCache cache = new PlayerDatabase.MojangCache(username, Instant.now());
		uuidMemory.put(uuid, cache);
		database.putMojangCache(uuid, username, cache.fetchedAt());
	}

	private static Optional<Profile> profileOf(PlayerDatabase.MojangNameCache cache) {
		if (cache.uuid() == null || cache.username() == null) {
			return Optional.empty();
		}
		return Optional.of(new Profile(cache.uuid(), cache.username()));
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
}
