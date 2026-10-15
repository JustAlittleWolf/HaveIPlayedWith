package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs activity writes off the client thread. A full queue returns false so the
 * observer can retry next tick. Closing drains accepted writes before the store closes.
 */
final class ActivityWriteQueue {
    private static final int CAPACITY = 2048;
    private final BlockingQueue<Runnable> writes = new ArrayBlockingQueue<>(CAPACITY);
    private final ExecutorService worker = ModThreads.singleWorker("activity-writes");

    ActivityWriteQueue() {
        worker.execute(this::drain);
    }

    synchronized boolean offer(Runnable write) {
        return !worker.isShutdown() && writes.offer(write);
    }

    void close() {
        synchronized (this) {
            worker.shutdown();
        }
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                ModLog.LOGGER.warn("Timed out draining activity minute writes");
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted() && (!worker.isShutdown() || !writes.isEmpty())) {
            try {
                Runnable write = writes.poll(100, TimeUnit.MILLISECONDS);
                if (write != null) {
                    write.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                ModLog.LOGGER.debug("Activity minute write failed", e);
            }
        }
    }
}
