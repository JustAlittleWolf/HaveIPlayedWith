package me.wolfii.haveiplayedwith.http;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window limiter. {@link #acquire()} blocks until a permit is available.
 */
public final class RateLimiter {
    private final int maxPermits;
    private final long windowNanos;
    private final ArrayDeque<Long> stamps = new ArrayDeque<>();

    public RateLimiter(int maxPermits, long window, TimeUnit unit) {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("maxPermits");
        }
        this.maxPermits = maxPermits;
        this.windowNanos = unit.toNanos(window);
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        evict(now);
        if (stamps.size() >= maxPermits) {
            return false;
        }
        stamps.addLast(now);
        return true;
    }

    public synchronized void acquire() throws InterruptedException {
        while (true) {
            long now = System.nanoTime();
            evict(now);
            if (stamps.size() < maxPermits) {
                stamps.addLast(now);
                return;
            }
            long oldest = stamps.peekFirst();
            long waitNanos = windowNanos - (now - oldest);
            long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(waitNanos) + 1L);
            wait(waitMillis);
        }
    }

    private void evict(long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst() >= windowNanos) {
            stamps.removeFirst();
        }
    }
}
