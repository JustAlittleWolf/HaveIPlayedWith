package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.UUID;

/**
 * Counts a minute as nearby when another player is within {@link #NEARBY_BLOCKS}
 * blocks, measured as a sphere so height counts. The local player is skipped.
 * Each player is written once per calendar minute, and the write only adds a nearby minute.
 */
final class ProximityObserver {
    static final double NEARBY_BLOCKS = 100.0;
    private static final double NEARBY_DISTANCE_SQR = NEARBY_BLOCKS * NEARBY_BLOCKS;
    private static final long MEMORY_MINUTES = 60;

    private final PlayerStore players;
    private final MinuteScheduler minutes;
    private final NotedMinutes noted = new NotedMinutes();
    private long currentMinute = Long.MIN_VALUE;

    ProximityObserver(PlayerStore players, MinuteScheduler minutes) {
        this.players = players;
        this.minutes = minutes;
    }

    static boolean nearby(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= NEARBY_DISTANCE_SQR;
    }

    void tick(Minecraft client, long epochMinute) {
        if (client.level == null || client.player == null) {
            return;
        }
        prune(epochMinute);
        LocalPlayer self = client.player;
        for (AbstractClientPlayer other : client.level.players()) {
            if (!withinRange(self, other)) {
                continue;
            }
            if (!offer(other.getGameProfile(), epochMinute)) {
                return;
            }
        }
    }

    void clear() {
        noted.clear();
        currentMinute = Long.MIN_VALUE;
    }

    private void prune(long epochMinute) {
        if (epochMinute == currentMinute) {
            return;
        }
        currentMinute = epochMinute;
        noted.pruneBefore(epochMinute - MEMORY_MINUTES);
    }

    private static boolean withinRange(LocalPlayer self, AbstractClientPlayer other) {
        if (other == null || other == self) {
            return false;
        }
        UUID uuid = other.getUUID();
        if (uuid == null || uuid.equals(self.getUUID())) {
            return false;
        }
        return nearby(other.getX() - self.getX(), other.getY() - self.getY(), other.getZ() - self.getZ());
    }

    /** @return {@code false} when the write queue is full and this tick should stop */
    private boolean offer(GameProfile profile, long epochMinute) {
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
        if (!minutes.offer(() -> players.recordNearbyMinute(uuid))) {
            return false;
        }
        noted.note(uuid, epochMinute);
        return true;
    }
}
