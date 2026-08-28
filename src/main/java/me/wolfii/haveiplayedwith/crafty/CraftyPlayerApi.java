package me.wolfii.haveiplayedwith.crafty;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.haveiplayedwith.http.JsonHttp;
import me.wolfii.haveiplayedwith.http.RateLimiter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Crafty.gg player search. Results stay in memory for the client run: an import walks
 * millions of lines, so disk is the wrong place for this cache.
 */
public final class CraftyPlayerApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
    private static final String SEARCH = "https://api.crafty.gg/api/v2/players/?search=";
    private final RateLimiter limiter = new RateLimiter(140, 1, TimeUnit.MINUTES);
    private final ConcurrentHashMap<String, CraftyPlayer> memory = new ConcurrentHashMap<>();

    private static Optional<CraftyPlayer> parse(String body, String searched) {
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
        return Optional.of(new CraftyPlayer(uuid, current, history, true));
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

    private static CraftyPlayer invalid() {
        return new CraftyPlayer(null, null, List.of(), false);
    }

    public Optional<CraftyPlayer> lookup(String username) {
        String key = CraftyNameHistory.cacheKey(username);
        CraftyPlayer remembered = memory.get(key);
        if (remembered != null) {
            return Optional.of(remembered);
        }
        Optional<CraftyPlayer> fetched = fetch(username);
        fetched.ifPresent(player -> memory.put(key, player));
        return fetched;
    }

    /** Test hook: skip the network and seed the in-memory cache. */
    void remember(String username, CraftyPlayer player) {
        memory.put(CraftyNameHistory.cacheKey(username), player);
    }

    int memorySize() {
        return memory.size();
    }

    private Optional<CraftyPlayer> fetch(String username) {
        try {
            limiter.acquire();
            String url = SEARCH + URLEncoder.encode(username, StandardCharsets.UTF_8);
            HttpResponse<String> response = JsonHttp.get(url);
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
}
