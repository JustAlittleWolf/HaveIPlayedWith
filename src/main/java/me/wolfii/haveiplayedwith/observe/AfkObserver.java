package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.mixin.FramerateLimitTrackerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Counts a minute as AFK when the client has had no input for {@link #AFK_AFTER_MS}.
 * That window belongs to this mod. The write only adds an AFK minute.
 */
final class AfkObserver extends PlayerMinuteObserver {
    static final long AFK_AFTER_MS = 60_000L;

    private final Predicate<UUID> enqueueMinute;

    AfkObserver(Predicate<UUID> enqueueMinute) {
        this.enqueueMinute = enqueueMinute;
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
        observeAllPlayers(client, epochMinute, profile -> enqueueMinute.test(profile.id()));
    }
}
