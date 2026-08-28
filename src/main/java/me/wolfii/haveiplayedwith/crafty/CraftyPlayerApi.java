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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Crafty.gg lookups for import. Usernames are reused, so a search for the current
 * holder is not enough: we load every account that has used the name, then pick the
 * one whose name-history interval covers the chat timestamp.
 */
public final class CraftyPlayerApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
    private static final String USERNAMES = "https://api.crafty.gg/api/v2/usernames/";
    private static final String PLAYER = "https://api.crafty.gg/api/v2/players/";
    private final RateLimiter limiter = new RateLimiter(140, 1, TimeUnit.MINUTES);
    private final ConcurrentHashMap<String, List<CraftyPlayer>> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CraftyPlayer> byUuid = new ConcurrentHashMap<>();

    static List<UUID> ownerUuids(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("success") && !root.get("success").getAsBoolean()) {
            return List.of();
        }
        if (!root.has("data") || !root.get("data").isJsonObject()) {
            return List.of();
        }
        JsonObject data = root.getAsJsonObject("data");
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        addUuid(ids, data.get("current_player"));
        if (data.has("historical_players") && data.get("historical_players").isJsonArray()) {
            for (JsonElement element : data.getAsJsonArray("historical_players")) {
                addUuid(ids, element);
            }
        }
        return List.copyOf(ids);
    }

    private static void addUuid(LinkedHashSet<UUID> ids, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("uuid") || object.get("uuid").isJsonNull()) {
            return;
        }
        ids.add(UUID.fromString(object.get("uuid").getAsString()));
    }

    static Optional<CraftyPlayer> parsePlayer(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("success") && !root.get("success").getAsBoolean()) {
            return Optional.empty();
        }
        if (!root.has("data") || root.get("data").isJsonNull() || !root.get("data").isJsonObject()) {
            return Optional.empty();
        }
        return Optional.of(playerFrom(root.getAsJsonObject("data")));
    }

    private static CraftyPlayer playerFrom(JsonObject data) {
        if (!data.has("uuid") || data.get("uuid").isJsonNull()) {
            return invalid();
        }
        UUID uuid = UUID.fromString(data.get("uuid").getAsString());
        String current = data.has("username") ? data.get("username").getAsString() : null;
        List<CraftyNameHistory.Entry> history = new ArrayList<>();
        if (data.has("usernames") && data.get("usernames").isJsonArray()) {
            JsonArray names = data.getAsJsonArray("usernames");
            for (JsonElement element : names) {
                if (!element.isJsonObject()) {
                    continue;
                }
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
        return new CraftyPlayer(uuid, current, history, true);
    }

    private static CraftyPlayer invalid() {
        return new CraftyPlayer(null, null, List.of(), false);
    }

    public Optional<CraftyPlayer> lookupHeld(String username, Instant at) {
        String key = CraftyNameHistory.cacheKey(username);
        List<CraftyPlayer> remembered = owners.get(key);
        if (remembered == null) {
            Optional<List<CraftyPlayer>> fetched = fetchOwners(username);
            if (fetched.isEmpty()) {
                return Optional.empty();
            }
            remembered = fetched.get();
            owners.put(key, remembered);
        }
        return CraftyNameHistory.holderAt(remembered, username, at);
    }

    /** Test hook: skip the network and seed the in-memory cache. */
    void remember(String username, CraftyPlayer... players) {
        owners.put(CraftyNameHistory.cacheKey(username), List.of(players));
    }

    int memorySize() {
        return owners.size();
    }

    private Optional<List<CraftyPlayer>> fetchOwners(String username) {
        try {
            limiter.acquire();
            String url = USERNAMES + URLEncoder.encode(username, StandardCharsets.UTF_8);
            HttpResponse<String> response = JsonHttp.get(url);
            int status = response.statusCode();
            if (status == 404) {
                return Optional.of(List.of());
            }
            if (status == 429) {
                LOGGER.debug("Crafty username lookup rate limited for {}", username);
                return Optional.empty();
            }
            if (status / 100 != 2) {
                LOGGER.debug("Crafty username lookup {} returned {}", username, status);
                return Optional.empty();
            }
            List<CraftyPlayer> players = new ArrayList<>();
            for (UUID uuid : ownerUuids(response.body())) {
                Optional<CraftyPlayer> player = player(uuid);
                if (player.isEmpty()) {
                    return Optional.empty();
                }
                if (player.get().valid()) {
                    players.add(player.get());
                }
            }
            return Optional.of(List.copyOf(players));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.debug("Crafty username lookup failed for {}", username, e);
            return Optional.empty();
        }
    }

    private Optional<CraftyPlayer> player(UUID uuid) {
        CraftyPlayer remembered = byUuid.get(uuid);
        if (remembered != null) {
            return Optional.of(remembered);
        }
        try {
            limiter.acquire();
            HttpResponse<String> response = JsonHttp.get(PLAYER + uuid);
            int status = response.statusCode();
            if (status == 404) {
                return Optional.of(invalid());
            }
            if (status == 429) {
                LOGGER.debug("Crafty player lookup rate limited for {}", uuid);
                return Optional.empty();
            }
            if (status / 100 != 2) {
                LOGGER.debug("Crafty player lookup {} returned {}", uuid, status);
                return Optional.empty();
            }
            Optional<CraftyPlayer> parsed = parsePlayer(response.body());
            CraftyPlayer player = parsed.filter(CraftyPlayer::valid).orElse(invalid());
            if (player.valid()) {
                byUuid.put(uuid, player);
            }
            return Optional.of(player);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.debug("Crafty player lookup failed for {}", uuid, e);
            return Optional.empty();
        }
    }
}
