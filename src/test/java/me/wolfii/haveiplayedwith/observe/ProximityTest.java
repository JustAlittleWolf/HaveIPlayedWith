package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProximityTest {
    @Test
    void oneHundredBlocksIsNearbyOnEveryAxis() {
        assertTrue(ProximityObserver.nearby(0, 0, 0));
        assertTrue(ProximityObserver.nearby(100, 0, 0));
        assertTrue(ProximityObserver.nearby(0, -100, 0));
        assertTrue(ProximityObserver.nearby(0, 0, 100));
        assertFalse(ProximityObserver.nearby(100.0001, 0, 0));
        assertFalse(ProximityObserver.nearby(0, 0, -100.0001));
    }

    @Test
    void diagonalUsesSphericalDistance() {
        assertTrue(ProximityObserver.nearby(50, 50, 70.7));
        assertFalse(ProximityObserver.nearby(60, 60, 60));
        assertFalse(ProximityObserver.nearby(100, 1, 0));
    }
}
