package me.wolfii.haveiplayedwith.store;

import java.time.Instant;

public record CraftyCache(String uuid, String currentUsername, String usernamesJson, boolean valid, Instant fetchedAt) {
}
