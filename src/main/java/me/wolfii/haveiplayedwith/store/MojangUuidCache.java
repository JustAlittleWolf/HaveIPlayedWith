package me.wolfii.haveiplayedwith.store;

import java.time.Instant;

public record MojangUuidCache(String username, Instant fetchedAt) {
}
