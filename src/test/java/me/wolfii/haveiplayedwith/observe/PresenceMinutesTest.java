package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceMinutesTest {
    private final UUID steve = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    @Test
    void nearbyAtCreditIsTakenOnce() {
        PresenceMinutes presence = new PresenceMinutes();
        presence.markNearby(steve, 10);
        assertTrue(presence.markCredited(steve, 10));
        assertTrue(presence.nearbyForCredit(steve, 10, false));
        assertFalse(presence.nearbyForCredit(steve, 10, true));
        assertTrue(presence.lateNearby(10).isEmpty());
    }

    @Test
    void arrivingLaterInTheMinuteCountsOnce() {
        PresenceMinutes presence = new PresenceMinutes();
        assertTrue(presence.markCredited(steve, 10));
        assertFalse(presence.nearbyForCredit(steve, 10, false));
        presence.markNearby(steve, 10);
        assertTrue(presence.lateNearby(10).contains(steve));
        assertTrue(presence.lateNearby(10).isEmpty());
        assertFalse(presence.nearbyForCredit(steve, 10, true));
    }

    @Test
    void lateNearbyWaitsUntilTheMinuteIsCredited() {
        PresenceMinutes presence = new PresenceMinutes();
        presence.markNearby(steve, 10);
        assertTrue(presence.lateNearby(10).isEmpty());
        assertTrue(presence.markCredited(steve, 10));
        assertTrue(presence.nearbyForCredit(steve, 10, false));
    }

    @Test
    void sightingSnapshotCountsAfterTheMapsAreCleared() {
        PresenceMinutes presence = new PresenceMinutes();
        presence.markNearby(steve, 10);
        presence.clear();
        assertTrue(presence.markCredited(steve, 10));
        assertTrue(presence.nearbyForCredit(steve, 10, true));
        assertFalse(presence.nearbyForCredit(steve, 10, true));
    }

    @Test
    void aNewMinuteCanBeCreditedAgain() {
        PresenceMinutes presence = new PresenceMinutes();
        presence.markNearby(steve, 10);
        assertTrue(presence.markCredited(steve, 10));
        assertFalse(presence.markCredited(steve, 10));
        presence.markNearby(steve, 11);
        assertTrue(presence.markCredited(steve, 11));
        assertTrue(presence.nearbyForCredit(steve, 11, false));
    }

    @Test
    void prunedMinutesDoNotCountAsNearby() {
        PresenceMinutes presence = new PresenceMinutes();
        presence.markNearby(steve, 1);
        presence.markCredited(steve, 1);
        presence.prune(2);
        assertTrue(presence.lateNearby(1).isEmpty());
        assertFalse(presence.wasNearby(steve, 1));
    }
}
