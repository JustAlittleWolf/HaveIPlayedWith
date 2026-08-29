package me.wolfii.haveiplayedwith.crafty;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.http.JsonAnswer;
import me.wolfii.haveiplayedwith.http.JsonApi;
import me.wolfii.haveiplayedwith.http.RateLimiter;

import java.net.URLEncoder;
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
    private static final String USERNAMES = "https://api.crafty.gg/api/v2/usernames/";
    private static final String PLAYER = "https://api.crafty.gg/api/v2/players/";
    private final JsonApi api = new JsonApi("Crafty", new RateLimiter(140, 1, TimeUnit.MINUTES));
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
            String url = USERNAMES + URLEncoder.encode(username, StandardCharsets.UTF_8);
            return switch (api.get(url)) {
                case JsonAnswer.Body body -> loadAll(ownerUuids(body.json()));
                case JsonAnswer.Missing ignored -> Optional.of(List.of());
                case JsonAnswer.Unavailable ignored -> Optional.empty();
            };
        } catch (RuntimeException e) {
            ModLog.LOGGER.debug("Crafty username lookup failed for {}", username, e);
            return Optional.empty();
        }
    }

    /** Empty unless every account behind the name loaded, so a name is never cached half-resolved. */
    private Optional<List<CraftyPlayer>> loadAll(List<UUID> uuids) {
        List<CraftyPlayer> players = new ArrayList<>();
        for (UUID uuid : uuids) {
            Optional<CraftyPlayer> player = player(uuid);
            if (player.isEmpty()) {
                return Optional.empty();
            }
            if (player.get().valid()) {
                players.add(player.get());
            }
        }
        return Optional.of(List.copyOf(players));
    }

    private Optional<CraftyPlayer> player(UUID uuid) {
        CraftyPlayer remembered = byUuid.get(uuid);
        if (remembered != null) {
            return Optional.of(remembered);
        }
        try {
            return switch (api.get(PLAYER + uuid)) {
                case JsonAnswer.Body body -> {
                    CraftyPlayer player = parsePlayer(body.json()).filter(CraftyPlayer::valid).orElse(invalid());
                    if (player.valid()) {
                        byUuid.put(uuid, player);
                    }
                    yield Optional.of(player);
                }
                case JsonAnswer.Missing ignored -> Optional.of(invalid());
                case JsonAnswer.Unavailable ignored -> Optional.empty();
            };
        } catch (RuntimeException e) {
            ModLog.LOGGER.debug("Crafty player lookup failed for {}", uuid, e);
            return Optional.empty();
        }
    }
}
