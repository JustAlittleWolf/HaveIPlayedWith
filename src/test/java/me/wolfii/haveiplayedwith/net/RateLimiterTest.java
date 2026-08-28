package me.wolfii.haveiplayedwith.net;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    @Test
    void rejectsWhenWindowIsFull() {
        RateLimiter limiter = new RateLimiter(2, 1, TimeUnit.HOURS);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }
}
