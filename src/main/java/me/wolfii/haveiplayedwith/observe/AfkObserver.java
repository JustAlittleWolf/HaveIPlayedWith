package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.mixin.FramerateLimitTrackerAccessor;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;

import java.util.function.Predicate;
import net.minecraft.util.Util;

/**
 * Counts a minute as AFK when the client has had no input for {@link #AFK_AFTER_MS}.
 * That window belongs to this mod. The write only adds an AFK minute.
 */
final class AfkObserver extends PlayerMinuteObserver {
    static final long AFK_AFTER_MS = 60_000L;

    private final PlayerStore playerStore;
    private final Predicate<Runnable> enqueueWrite;

    AfkObserver(PlayerStore playerStore, Predicate<Runnable> enqueueWrite) {
        this.playerStore = playerStore;
        this.enqueueWrite = enqueueWrite;
    }

    static boolean isAfk(long latestInputMillis, long nowMillis) {
        return nowMillis - latestInputMillis >= AFK_AFTER_MS;
    }

    static boolean isClientAfk(Minecraft client) {
        long latestInputMillis = ((FramerateLimitTrackerAccessor) client.getFramerateLimitTracker()).haveiplayedwith$latestInputTime();
        return isAfk(latestInputMillis, Util.getMillis());
    }

    @Override
    void tick(Minecraft client, long epochMinute, String serverId) {
        prune(epochMinute);
        if (!isClientAfk(client)) {
            return;
        }
        observeAllPlayers(client, epochMinute, profile -> enqueueWrite.test(() -> playerStore.recordAfkMinute(profile.id())));
    }
}
