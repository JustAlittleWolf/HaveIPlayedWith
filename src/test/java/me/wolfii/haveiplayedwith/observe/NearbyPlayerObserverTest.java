package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyPlayerObserverTest {
    @Test
    void oneHundredBlocksIsNearbyOnEveryAxis() {
        assertTrue(NearbyPlayerObserver.isNearby(0, 0, 0));
        assertTrue(NearbyPlayerObserver.isNearby(100, 0, 0));
        assertTrue(NearbyPlayerObserver.isNearby(0, -100, 0));
        assertTrue(NearbyPlayerObserver.isNearby(0, 0, 100));
        assertFalse(NearbyPlayerObserver.isNearby(100.0001, 0, 0));
        assertFalse(NearbyPlayerObserver.isNearby(0, 0, -100.0001));
    }

    @Test
    void diagonalUsesSphericalDistance() {
        assertTrue(NearbyPlayerObserver.isNearby(50, 50, 70.7));
        assertFalse(NearbyPlayerObserver.isNearby(60, 60, 60));
        assertFalse(NearbyPlayerObserver.isNearby(100, 1, 0));
    }
}
