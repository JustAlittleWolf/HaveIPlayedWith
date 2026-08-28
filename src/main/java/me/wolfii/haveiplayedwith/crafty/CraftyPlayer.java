package me.wolfii.haveiplayedwith.crafty;

import java.util.List;
import java.util.UUID;

public record CraftyPlayer(UUID uuid, String currentUsername, List<CraftyNameHistory.Entry> history, boolean valid) {
}
