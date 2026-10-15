package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;

/**
 * Runs minute writes off the client thread. Offering never blocks. A full queue
 * returns false so the caller can leave that player unnoted and retry next tick.
 * The store itself still serializes the actual row write.
 */
final class MinuteScheduler {
    private static final int MAX_BUFFER = 2048;
    private final BlockingQueue<Runnable> writes = new ArrayBlockingQueue<>(MAX_BUFFER);
    private final ExecutorService worker = ModThreads.singleWorker("minutes");

    MinuteScheduler() {
        worker.execute(this::drain);
    }

    boolean offer(Runnable write) {
        return writes.offer(write);
    }

    void close() {
        worker.shutdownNow();
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                writes.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                ModLog.LOGGER.debug("Minute write failed", e);
            }
        }
    }
}
