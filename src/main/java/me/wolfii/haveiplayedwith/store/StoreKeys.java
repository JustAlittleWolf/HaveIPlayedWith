package me.wolfii.haveiplayedwith.store;

import java.util.Locale;
import java.util.UUID;

final class StoreKeys {
    private StoreKeys() {
    }

    static String uuid(UUID uuid) {
        return uuid.toString();
    }

    static String nameIndex(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
