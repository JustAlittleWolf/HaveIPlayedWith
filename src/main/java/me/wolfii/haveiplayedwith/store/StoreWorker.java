package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import org.h2.mvstore.MVStore;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Serializes every store read, write, compact, and pending flush onto one thread.
 */
final class StoreWorker implements AutoCloseable {
    /** How often to look at in-memory fill stats. The check itself does not read rows. */
    private static final long COMPACT_PERIOD_SECONDS = 900;
    /** How often to write coalesced minute ticks so a crash does not drop a long session. */
    private static final long FLUSH_PERIOD_SECONDS = 300;

    private final ScheduledExecutorService worker = ModThreads.singleScheduledWorker("db");
    private MVStore store;
    private ScheduledFuture<?> compactTask;
    private ScheduledFuture<?> flushTask;

    void use(MVStore store) {
        this.store = store;
    }

    void scheduleFlush(Runnable flush) {
        flushTask = worker.scheduleWithFixedDelay(() -> {
            try {
                flush.run();
            } catch (RuntimeException e) {
                ModLog.LOGGER.warn("HaveIPlayedWith store flush failed", e);
            }
        }, FLUSH_PERIOD_SECONDS, FLUSH_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    void scheduleCompact(Runnable compact) {
        compactTask = worker.scheduleWithFixedDelay(() -> {
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
        if (flushTask != null) {
            flushTask.cancel(false);
        }
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
            if (store != null && !store.isClosed()) {
                store.close();
            }
        });
    }

    private void closeQuietly() {
        try {
            if (store != null && !store.isClosed()) {
                store.close();
            }
        } catch (Exception e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith database", e);
        }
    }
}