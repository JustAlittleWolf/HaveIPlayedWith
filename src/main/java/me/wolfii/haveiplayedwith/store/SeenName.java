package me.wolfii.haveiplayedwith.store;

import java.time.Instant;

public record SeenName(String username, Instant lastSeen) {
}
