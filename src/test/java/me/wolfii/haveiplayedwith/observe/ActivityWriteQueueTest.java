package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ActivityWriteQueueTest {
    @Test
    void closeDrainsAcceptedWritesAndRejectsNewOnes() throws Exception {
        ActivityWriteQueue queue = new ActivityWriteQueue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        try {
            assertTrue(queue.offer(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(queue.offer(completed::incrementAndGet));
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(release::countDown);
            queue.close();
            assertEquals(1, completed.get());
            assertFalse(queue.offer(completed::incrementAndGet));
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test
    void aFailedWriteDoesNotStopLaterWrites() {
        ActivityWriteQueue queue = new ActivityWriteQueue();
        AtomicInteger completed = new AtomicInteger();
        assertTrue(queue.offer(() -> { throw new IllegalStateException("test failure"); }));
        assertTrue(queue.offer(completed::incrementAndGet));
        queue.close();
        assertEquals(1, completed.get());
    }
}
