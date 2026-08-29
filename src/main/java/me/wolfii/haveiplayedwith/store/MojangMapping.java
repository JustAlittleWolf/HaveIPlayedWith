package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.util.UUID;

/** One Mojang UUID ↔ name row, or a cached miss when either side is empty. */
public record MojangMapping(UUID uuid, String username, Instant lastValid) {
    public boolean resolved() {
        return uuid != null && username != null && !username.isBlank();
    }
}
