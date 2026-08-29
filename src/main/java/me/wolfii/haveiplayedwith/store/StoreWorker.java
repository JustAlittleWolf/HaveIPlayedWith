package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import org.dizitart.no2.Nitrite;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Serializes every store read, write, and compact onto one thread. MVStore
 * auto-commit flushes to disk in the background; {@link Nitrite#close()} writes
 * anything still pending.
 */
final class StoreWorker implements AutoCloseable {
    /** How often to look at in-memory fill stats. The check itself does not read documents. */
    private static final long COMPACT_PERIOD_SECONDS = 120;
    /** Wait this long after the last store op so a rewrite does not stall a live tick. */
    private static final long COMPACT_IDLE_MS = 15_000;

    private final ScheduledExecutorService worker = ModThreads.singleScheduledWorker("db");
    private Nitrite nitrite;
    private ScheduledFuture<?> compactTask;
    private volatile long lastWorkMs = System.currentTimeMillis();

    void use(Nitrite nitrite) {
        this.nitrite = nitrite;
    }

    void scheduleCompact(Runnable compact) {
        compactTask = worker.scheduleWithFixedDelay(() -> {
            if (System.currentTimeMillis() - lastWorkMs < COMPACT_IDLE_MS) {
                return;
            }
            try {
                compact.run();
            } catch (RuntimeException e) {
                ModLog.LOGGER.warn("HaveIPlayedWith store compact failed", e);
            }
        }, COMPACT_PERIOD_SECONDS, COMPACT_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(cause);
    }

    <T> T call(Callable<T> task) {
        lastWorkMs = System.currentTimeMillis();
        try {
            return worker.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    void run(StoreWork task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    void close(StoreWork shutdown) {
        if (compactTask != null) {
            compactTask.cancel(false);
        }
        try {
            worker.submit(() -> {
                shutdown.run();
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly();
        } catch (Exception e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith database", e);
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    @Override
    public void close() {
        close(() -> {
            if (nitrite != null && !nitrite.isClosed()) {
                nitrite.close();
            }
        });
    }

    private void closeQuietly() {
        try {
            if (nitrite != null && !nitrite.isClosed()) {
                nitrite.close();
            }
        } catch (Exception e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith database", e);
        }
    }
}
