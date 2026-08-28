package me.wolfii.db;

import java.time.Instant;

public record SeenName(String username, Instant lastSeen) {
}
