package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.UUID;

/**
 * Counts a minute as nearby when another player is within {@link #NEARBY_BLOCKS}
 * blocks, measured as a sphere so height counts. The local player is skipped.
 * The write only adds a nearby minute.
 */
final class ProximityObserver extends MinuteObserver {
    static final double NEARBY_BLOCKS = 100.0;
    private static final double NEARBY_DISTANCE_SQR = NEARBY_BLOCKS * NEARBY_BLOCKS;

    ProximityObserver(PlayerStore players, MinuteScheduler minutes) {
        super(players, minutes);
    }

    static boolean nearby(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= NEARBY_DISTANCE_SQR;
    }

    @Override
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
            if (!offer(other.getGameProfile(), epochMinute, uuid -> minutes.offer(() -> players.recordNearbyMinute(uuid)))) {
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
        return nearby(other.getX() - self.getX(), other.getY() - self.getY(), other.getZ() - self.getZ());
    }
}
