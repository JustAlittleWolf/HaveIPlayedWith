package me.wolfii.haveiplayedwith.store;

import java.time.Instant;

public record MojangCache(String username, Instant fetchedAt) {
}
