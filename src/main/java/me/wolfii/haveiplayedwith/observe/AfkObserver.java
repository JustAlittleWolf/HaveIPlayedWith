package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.mixin.FramerateLimitTrackerAccessor;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import me.wolfii.haveiplayedwith.store.PlayerActivity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Counts a minute as AFK when the client has had no input for {@link #AFK_AFTER_MS}.
 * That window belongs to this mod. The write only adds an AFK minute.
 */
final class AfkObserver extends ActivityMinuteObserver {
    static final long AFK_AFTER_MS = 60_000L;

    AfkObserver(PlayerStore players, ActivityWriteQueue activityWrites) {
        super(players, activityWrites, PlayerActivity.AFK);
    }

    static boolean isAfk(long latestInputMillis, long nowMillis) {
        return nowMillis - latestInputMillis >= AFK_AFTER_MS;
    }

    static boolean isClientAfk(Minecraft client) {
        long latestInputMillis = ((FramerateLimitTrackerAccessor) client.getFramerateLimitTracker()).haveiplayedwith$latestInputTime();
        return isAfk(latestInputMillis, Util.getMillis());
    }

    @Override
    void tick(Minecraft client, long epochMinute) {
        if (client.level == null || client.player == null || client.getConnection() == null) {
            return;
        }
        prune(epochMinute);
        if (!isClientAfk(client)) {
            return;
        }
        observeAllPlayers(client, epochMinute, profile -> enqueueMinute(profile.id()));
    }
}
