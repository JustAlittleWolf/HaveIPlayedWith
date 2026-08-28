package me.wolfii.haveiplayedwith.store;

import org.h2.mvstore.MVMap;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * MVStore key formats.
 */
final class StoreKeys {
    private StoreKeys() {
    }

    static String uuid(UUID uuid) {
        return uuid.toString();
    }

    static String prefix(UUID uuid) {
        return uuid + "\t";
    }

    static String history(UUID uuid, String username) {
        return uuid + "\t" + username.toLowerCase(Locale.ROOT);
    }

    static String session(UUID uuid, String sessionId) {
        return uuid + "\t" + sessionId;
    }

    static String playDay(UUID uuid, LocalDate day) {
        return uuid + "\t" + day;
    }

    static LocalDate playDayOf(String key, String prefix) {
        return LocalDate.parse(key.substring(prefix.length()));
    }

    static String server(UUID uuid, String serverId) {
        return uuid + "\t" + serverId;
    }

    static String nameIndexPrefix(String username) {
        return username.toLowerCase(Locale.ROOT) + "\t";
    }

    static String nameIndex(String username, UUID uuid) {
        return nameIndexPrefix(username) + uuid;
    }

    static UUID nameIndexUuid(String key, String prefix) {
        return UUID.fromString(key.substring(prefix.length()));
    }

    static int countPrefix(MVMap<String, ?> map, String prefix) {
        int count = 0;
        Iterator<String> keys = map.keyIterator(prefix);
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            count++;
        }
        return count;
    }
}
