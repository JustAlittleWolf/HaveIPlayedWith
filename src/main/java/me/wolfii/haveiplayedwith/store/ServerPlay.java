package me.wolfii.haveiplayedwith.store;

/**
 * Minutes credited while playing with this person on one server or local world.
 * Local worlds are stored as {@code world/{worldname}}.
 */
public record ServerPlay(String serverId, long minutes) {
}
