package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * One extra minute counter. A player is queued once per calendar minute, and a full
 * queue leaves them unnoted so the next tick can retry. The write itself never changes
 * play time.
 */
abstract class MinuteObserver {
    private static final long MEMORY_MINUTES = 60;

    final PlayerStore players;
    private final MinuteScheduler minutes;
    private final NotedMinutes noted = new NotedMinutes();
    private long currentMinute = Long.MIN_VALUE;

    MinuteObserver(PlayerStore players, MinuteScheduler minutes) {
        this.players = players;
        this.minutes = minutes;
    }

    abstract void tick(Minecraft client, long epochMinute);

    final void clear() {
        noted.clear();
        currentMinute = Long.MIN_VALUE;
    }

    final void prune(long epochMinute) {
        if (epochMinute == currentMinute) {
            return;
        }
        currentMinute = epochMinute;
        noted.pruneBefore(epochMinute - MEMORY_MINUTES);
    }

    /**
     * @return {@code false} when the write queue is full and this tick should stop
     */
    final boolean offer(GameProfile profile, long epochMinute, Consumer<UUID> write) {
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
        if (!minutes.offer(() -> write.accept(uuid))) {
            return false;
        }
        noted.note(uuid, epochMinute);
        return true;
    }
}
