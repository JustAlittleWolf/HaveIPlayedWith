package me.wolfii.haveiplayedwith.observe;

/**
 * How long the client can go without input before a minute counts as AFK.
 * The window is this mod's own; it is not Minecraft's framerate-throttle threshold.
 */
final class Afk {
    static final long AFK_AFTER_MS = 60_000L;

    private Afk() {
    }

    static boolean inactive(long latestInputMillis, long nowMillis) {
        return nowMillis - latestInputMillis >= AFK_AFTER_MS;
    }
}
