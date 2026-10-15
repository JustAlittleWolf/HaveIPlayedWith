package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

/**
 * Spherical distance used to decide that another player is actually with you,
 * rather than only sharing the same server or world. Each nearby player is
 * queued once per calendar minute, and that write only adds a nearby minute.
 */
final class Proximity {
    static final double NEARBY_BLOCKS = 100.0;
    private static final double NEARBY_DISTANCE_SQR = NEARBY_BLOCKS * NEARBY_BLOCKS;

    private final NotedMinutes noted = new NotedMinutes();
    private final Set<UUID> nearbyNow = new HashSet<>();

    static boolean nearby(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= NEARBY_DISTANCE_SQR;
    }

    void markNearby(LocalPlayer self, Iterable<AbstractClientPlayer> others) {
        nearbyNow.clear();
        for (AbstractClientPlayer other : others) {
            if (other == null || other == self) {
                continue;
            }
            UUID uuid = other.getUUID();
            if (uuid == null || uuid.equals(self.getUUID())) {
                continue;
            }
            if (nearby(other.getX() - self.getX(), other.getY() - self.getY(), other.getZ() - self.getZ())) {
                nearbyNow.add(uuid);
            }
        }
    }

    void clear() {
        noted.clear();
        nearbyNow.clear();
    }

    void pruneBefore(long minute) {
        noted.pruneBefore(minute);
    }

    /** @return {@code false} when the notice could not be queued and this tick should stop */
    boolean offer(BlockingQueue<Notice> out, UUID uuid, long minute) {
        if (!nearbyNow.contains(uuid) || noted.noted(uuid, minute)) {
            return true;
        }
        return noted.remember(out, uuid, minute, new Minute(uuid));
    }

    private record Minute(UUID uuid) implements Notice {
        @Override
        public boolean record(PlayerStore players) {
            players.recordNearbyMinute(uuid);
            return true;
        }
    }
}
