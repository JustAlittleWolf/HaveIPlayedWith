package me.wolfii.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.importing.CraftyNameHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Crafty.gg player search. Cached, and limited to 140 requests per minute (API allows 150).
 */
public final class CraftyClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
	private static final String SEARCH = "https://api.crafty.gg/api/v2/players/?search=";
	private final PlayerDatabase database;
	private final RateLimiter limiter = new RateLimiter(140, 1, TimeUnit.MINUTES);

	public record Player(UUID uuid, String currentUsername, List<CraftyNameHistory.Entry> history, boolean valid) {
	}

	public CraftyClient(PlayerDatabase database) {
		this.database = database;
	}

	public Optional<Player> lookup(String username) {
		String key = CraftyNameHistory.cacheKey(username);
		Optional<PlayerDatabase.CraftyCache> cached = database.craftyCache(key);
		if (cached.isPresent()) {
			return fromCache(cached.get());
		}
		Optional<Player> fetched = fetch(username);
		store(key, fetched);
		return fetched;
	}

	private Optional<Player> fetch(String username) {
		try {
			limiter.acquire();
			String url = SEARCH + URLEncoder.encode(username, StandardCharsets.UTF_8);
			HttpResponse<String> response = HttpJson.get(url);
			int status = response.statusCode();
			if (status == 404) {
				return Optional.of(invalid());
			}
			if (status == 429) {
				LOGGER.debug("Crafty lookup rate limited for {}", username);
				return Optional.empty();
			}
			if (status / 100 != 2) {
				LOGGER.debug("Crafty lookup {} returned {}", username, status);
				return Optional.empty();
			}
			return parse(response.body(), username);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			LOGGER.debug("Crafty lookup failed for {}", username, e);
			return Optional.empty();
		}
	}

	private void store(String key, Optional<Player> fetched) {
		if (fetched.isEmpty()) {
			return;
		}
		Player player = fetched.get();
		database.putCraftyCache(key, new PlayerDatabase.CraftyCache(
			player.uuid() == null ? null : player.uuid().toString(),
			player.currentUsername(),
			historyJson(player.history()),
			player.valid(),
			Instant.now()
		));
	}

	private static Optional<Player> fromCache(PlayerDatabase.CraftyCache cache) {
		if (!cache.valid()) {
			return Optional.of(invalid());
		}
		if (cache.uuid() == null) {
			return Optional.of(invalid());
		}
		return Optional.of(new Player(
			UUID.fromString(cache.uuid()),
			cache.currentUsername(),
			parseHistory(cache.usernamesJson()),
			true
		));
	}

	private static Optional<Player> parse(String body, String searched) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		if (root.has("success") && !root.get("success").getAsBoolean()) {
			return Optional.of(invalid());
		}
		if (!root.has("data") || root.get("data").isJsonNull()) {
			return Optional.of(invalid());
		}
		JsonElement dataElement = root.get("data");
		JsonObject data;
		if (dataElement.isJsonArray()) {
			JsonArray array = dataElement.getAsJsonArray();
			if (array.isEmpty()) {
				return Optional.of(invalid());
			}
			data = pickMatch(array, searched);
			if (data == null) {
				return Optional.of(invalid());
			}
		} else if (dataElement.isJsonObject()) {
			data = dataElement.getAsJsonObject();
		} else {
			return Optional.of(invalid());
		}
		if (!data.has("uuid") || data.get("uuid").isJsonNull()) {
			return Optional.of(invalid());
		}
		UUID uuid = UUID.fromString(data.get("uuid").getAsString());
		String current = data.has("username") ? data.get("username").getAsString() : searched;
		List<CraftyNameHistory.Entry> history = new ArrayList<>();
		if (data.has("usernames") && data.get("usernames").isJsonArray()) {
			for (JsonElement element : data.getAsJsonArray("usernames")) {
				JsonObject row = element.getAsJsonObject();
				String name = row.has("username") ? row.get("username").getAsString() : null;
				Instant changed = row.has("changed_at") && !row.get("changed_at").isJsonNull()
					? CraftyNameHistory.parseCraftyTime(row.get("changed_at").getAsString())
					: null;
				if (name != null) {
					history.add(new CraftyNameHistory.Entry(name, changed));
				}
			}
		}
		return Optional.of(new Player(uuid, current, history, true));
	}

	private static JsonObject pickMatch(JsonArray array, String searched) {
		JsonObject fallback = null;
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			if (fallback == null) {
				fallback = object;
			}
			if (object.has("username") && searched.equalsIgnoreCase(object.get("username").getAsString())) {
				return object;
			}
		}
		return fallback;
	}

	private static List<CraftyNameHistory.Entry> parseHistory(String json) {
		List<CraftyNameHistory.Entry> history = new ArrayList<>();
		if (json == null || json.isBlank()) {
			return history;
		}
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		for (JsonElement element : array) {
			JsonObject row = element.getAsJsonObject();
			Instant changed = row.has("changed_at") && !row.get("changed_at").isJsonNull()
				? CraftyNameHistory.parseCraftyTime(row.get("changed_at").getAsString())
				: null;
			history.add(new CraftyNameHistory.Entry(row.get("username").getAsString(), changed));
		}
		return history;
	}

	private static String historyJson(List<CraftyNameHistory.Entry> history) {
		JsonArray array = new JsonArray();
		for (CraftyNameHistory.Entry entry : history) {
			JsonObject row = new JsonObject();
			row.addProperty("username", entry.username());
			if (entry.changedAt() == null) {
				row.add("changed_at", null);
			} else {
				row.addProperty("changed_at", entry.changedAt().toString());
			}
			array.add(row);
		}
		return array.toString();
	}

	private static Player invalid() {
		return new Player(null, null, List.of(), false);
	}
}
