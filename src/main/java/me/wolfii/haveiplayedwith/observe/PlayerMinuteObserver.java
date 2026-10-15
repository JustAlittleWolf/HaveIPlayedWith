package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * One once-a-minute pass over the players in view. A player is queued once per
 * calendar minute. If the queue is full, the next tick can retry.
 */
abstract class PlayerMinuteObserver {
    static final long MEMORY_MINUTES = 60;

    private final QueuedPlayerMinutes queuedMinutes = new QueuedPlayerMinutes();
    private long currentMinute = Long.MIN_VALUE;

    abstract void tick(Minecraft client, long epochMinute);

    /** Shares the tab-list, loaded-player, and local-player traversal for play and AFK. */
    final void observeAllPlayers(Minecraft client, long epochMinute, Predicate<GameProfile> enqueue) {
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            if (!offer(info.getProfile(), epochMinute, enqueue)) {
                return;
            }
        }
        for (AbstractClientPlayer player : client.level.players()) {
            if (!offer(player.getGameProfile(), epochMinute, enqueue)) {
                return;
            }
        }
        GameProfile self = client.player.getGameProfile();
        offer(self, epochMinute, enqueue);
    }

    /** Rolls back collection when a batch could not be handed to the worker. */
    final void forgetQueuedMinute(UUID uuid, long epochMinute) {
        queuedMinutes.forget(uuid, epochMinute);
    }

    void clear() {
        queuedMinutes.clear();
        currentMinute = Long.MIN_VALUE;
    }

    /** @return {@code true} when {@code epochMinute} is a new minute and old entries were dropped */
    final boolean prune(long epochMinute) {
        if (epochMinute == currentMinute) {
            return false;
        }
        currentMinute = epochMinute;
        queuedMinutes.pruneBefore(epochMinute - MEMORY_MINUTES);
        return true;
    }

    /**
     * @return {@code false} when the write queue is full and this tick should stop
     */
    final boolean offer(GameProfile profile, long epochMinute, Predicate<GameProfile> enqueue) {
        if (profile == null) {
            return true;
        }
        UUID uuid = profile.id();
        if (uuid == null || !MinecraftUsernames.isValid(profile.name())) {
            return true;
        }
        if (queuedMinutes.wasQueued(uuid, epochMinute)) {
            return true;
        }
        if (!enqueue.test(profile)) {
            return false;
        }
        queuedMinutes.markQueued(uuid, epochMinute);
        return true;
    }
}
