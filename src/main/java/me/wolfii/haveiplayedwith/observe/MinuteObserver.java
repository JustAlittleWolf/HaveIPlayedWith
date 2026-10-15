package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * One once-a-minute pass over the players in view. A player is queued once per
 * calendar minute, and a full queue leaves them unnoted so the next tick can retry.
 */
abstract class MinuteObserver {
    static final long MEMORY_MINUTES = 60;

    final PlayerStore players;
    final MinuteScheduler minutes;
    private final NotedMinutes noted = new NotedMinutes();
    private long currentMinute = Long.MIN_VALUE;

    MinuteObserver(PlayerStore players, MinuteScheduler minutes) {
        this.players = players;
        this.minutes = minutes;
    }

    abstract void tick(Minecraft client, long epochMinute);

    void clear() {
        noted.clear();
        currentMinute = Long.MIN_VALUE;
    }

    /** @return {@code true} when {@code epochMinute} is a new minute and old entries were dropped */
    final boolean prune(long epochMinute) {
        if (epochMinute == currentMinute) {
            return false;
        }
        currentMinute = epochMinute;
        noted.pruneBefore(epochMinute - MEMORY_MINUTES);
        return true;
    }

    /**
     * @return {@code false} when the write queue is full and this tick should stop
     */
    final boolean offer(GameProfile profile, long epochMinute, Predicate<UUID> enqueue) {
        if (profile == null) {
            return true;
        }
        UUID uuid = profile.id();
        if (uuid == null || !MinecraftUsernames.isValid(profile.name())) {
            return true;
        }
        if (noted.noted(uuid, epochMinute)) {
            return true;
        }
        if (!enqueue.test(uuid)) {
            return false;
        }
        noted.note(uuid, epochMinute);
        return true;
    }
}
