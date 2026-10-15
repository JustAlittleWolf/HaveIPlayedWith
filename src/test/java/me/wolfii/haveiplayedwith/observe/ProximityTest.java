package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProximityTest {
    @Test
    void oneHundredBlocksIsNearbyOnEveryAxis() {
        assertTrue(Proximity.nearby(0, 0, 0));
        assertTrue(Proximity.nearby(100, 0, 0));
        assertTrue(Proximity.nearby(0, -100, 0));
        assertTrue(Proximity.nearby(0, 0, 100));
        assertFalse(Proximity.nearby(100.0001, 0, 0));
        assertFalse(Proximity.nearby(0, 0, -100.0001));
    }

    @Test
    void diagonalUsesSphericalDistance() {
        assertTrue(Proximity.nearby(50, 50, 70.7));
        assertFalse(Proximity.nearby(60, 60, 60));
        assertFalse(Proximity.nearby(100, 1, 0));
    }
}
