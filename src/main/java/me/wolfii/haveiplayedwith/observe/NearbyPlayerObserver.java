package me.wolfii.haveiplayedwith.observe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Counts a minute as nearby when another player is within {@link #NEARBY_RADIUS_BLOCKS}
 * blocks, measured as a sphere so height counts. The local player is skipped.
 * The write only adds a nearby minute.
 */
final class NearbyPlayerObserver extends PlayerMinuteObserver {
    static final double NEARBY_RADIUS_BLOCKS = 100.0;
    private static final double NEARBY_RADIUS_SQUARED = NEARBY_RADIUS_BLOCKS * NEARBY_RADIUS_BLOCKS;

    private final Predicate<UUID> enqueueMinute;

    NearbyPlayerObserver(Predicate<UUID> enqueueMinute) {
        this.enqueueMinute = enqueueMinute;
    }

    static boolean isNearby(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= NEARBY_RADIUS_SQUARED;
    }

    @Override
    void tick(Minecraft client, long epochMinute, String serverId) {
        prune(epochMinute);
        LocalPlayer self = client.player;
        for (AbstractClientPlayer other : client.level.players()) {
            if (!withinRange(self, other)) {
                continue;
            }
            if (!offer(other.getGameProfile(), epochMinute, profile -> enqueueMinute.test(profile.id()))) {
                return;
            }
        }
    }

    private static boolean withinRange(LocalPlayer self, AbstractClientPlayer other) {
        if (other == null || other == self) {
            return false;
        }
        UUID uuid = other.getUUID();
        if (uuid == null || uuid.equals(self.getUUID())) {
            return false;
        }
        return isNearby(other.getX() - self.getX(), other.getY() - self.getY(), other.getZ() - self.getZ());
    }
}
