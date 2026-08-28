package me.wolfii.db;

import java.time.Instant;
import java.util.UUID;

public record SeenName(String username, Instant lastSeen) {
}
