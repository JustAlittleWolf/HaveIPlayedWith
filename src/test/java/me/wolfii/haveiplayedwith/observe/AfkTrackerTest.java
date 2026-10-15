package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfkTrackerTest {
    @Test
    void idleThresholdIsOneMinuteOfTicks() {
        assertEquals(20 * 60, AfkTracker.IDLE_TICKS);
    }

    @Test
    void staysActiveUntilIdleTicksElapse() {
        AfkTracker tracker = new AfkTracker(3);
        assertFalse(tracker.tick(false));
        assertFalse(tracker.tick(false));
        assertTrue(tracker.tick(false));
        assertTrue(tracker.tick(false));
        assertTrue(tracker.afk());
    }

    @Test
    void activityClearsIdle() {
        AfkTracker tracker = new AfkTracker(2);
        assertFalse(tracker.tick(false));
        assertTrue(tracker.tick(false));
        assertFalse(tracker.tick(true));
        assertFalse(tracker.afk());
        assertFalse(tracker.tick(false));
        assertTrue(tracker.tick(false));
    }
}
