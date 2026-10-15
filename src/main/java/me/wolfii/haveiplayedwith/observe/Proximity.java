package me.wolfii.haveiplayedwith.observe;

/**
 * Spherical distance used to decide that another player is actually with you,
 * rather than only sharing the same server or world.
 */
final class Proximity {
    static final double NEARBY_BLOCKS = 100.0;
    private static final double NEARBY_DISTANCE_SQR = NEARBY_BLOCKS * NEARBY_BLOCKS;

    private Proximity() {
    }

    static boolean nearby(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= NEARBY_DISTANCE_SQR;
    }
}
