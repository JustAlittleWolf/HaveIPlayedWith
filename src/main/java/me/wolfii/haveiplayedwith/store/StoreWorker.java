package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import org.h2.mvstore.MVStore;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serializes every store read and write onto one thread.
 */
final class StoreWorker implements AutoCloseable {
    private final ExecutorService worker = ModThreads.singleWorker("db");
    private MVStore store;

    void use(MVStore store) {
        this.store = store;
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