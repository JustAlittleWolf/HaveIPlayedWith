package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.util.UUID;

public record MojangNameCache(UUID uuid, String username, Instant fetchedAt) {
}
