package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import org.dizitart.no2.Nitrite;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serializes every store read and write onto one thread. MVStore auto-commit
 * flushes to disk in the background; {@link Nitrite#close()} writes anything still pending.
 */
final class StoreWorker implements AutoCloseable {
    private final ExecutorService worker = ModThreads.singleWorker("db");
    private final Nitrite nitrite;

    StoreWorker(Nitrite nitrite) {
        this.nitrite = nitrite;
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

    @Override
    public void close() {
        try {
            worker.submit(() -> {
                nitrite.close();
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
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    private void closeQuietly() {
        try {
            nitrite.close();
        } catch (Exception e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith database", e);
        }
    }
}
