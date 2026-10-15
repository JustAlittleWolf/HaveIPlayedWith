package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.mixin.FramerateLimitTrackerAccessor;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;

/**
 * How long the client can go without input before a minute counts as AFK.
 * The window is this mod's own; it is not Minecraft's framerate-throttle threshold.
 * Each player is queued once per calendar minute, and that write only adds an AFK minute.
 */
final class Afk {
    static final long AFK_AFTER_MS = 60_000L;

    private final NotedMinutes noted = new NotedMinutes();
    private boolean afkNow;

    static boolean inactive(long latestInputMillis, long nowMillis) {
        return nowMillis - latestInputMillis >= AFK_AFTER_MS;
    }

    static boolean clientAfk(Minecraft client) {
        long latestInput = ((FramerateLimitTrackerAccessor) client.getFramerateLimitTracker()).haveiplayedwith$latestInputTime();
        return inactive(latestInput, Util.getMillis());
    }

    /** Reads the client once for the offers that follow in this tick. */
    void read(Minecraft client) {
        afkNow = clientAfk(client);
    }

    void clear() {
        noted.clear();
    }

    void pruneBefore(long minute) {
        noted.pruneBefore(minute);
    }

    /** @return {@code false} when the notice could not be queued and this tick should stop */
    boolean offer(BlockingQueue<Notice> out, UUID uuid, long minute) {
        if (!afkNow || noted.noted(uuid, minute)) {
            return true;
        }
        return noted.remember(out, uuid, minute, new Minute(uuid));
    }

    private record Minute(UUID uuid) implements Notice {
        @Override
        public boolean record(PlayerStore players) {
            players.recordAfkMinute(uuid);
            return true;
        }
    }
}
