package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serializes every store read and write onto one thread and commits after each task.
 */
final class StoreWorker implements AutoCloseable {
    private final ExecutorService worker = ModThreads.singleWorker("db");
    private final Connection connection;

    StoreWorker(Connection connection) {
        this.connection = connection;
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
            return worker.submit(() -> {
                try {
                    T result = task.call();
                    connection.commit();
                    return result;
                } catch (Exception e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        e.addSuppressed(rollback);
                    }
                    throw e;
                }
            }).get();
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
            run(connection::commit);
        } catch (RuntimeException e) {
            ModLog.LOGGER.warn("Failed to commit HaveIPlayedWith database", e);
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
            connection.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly();
        } catch (SQLException e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith database", e);
        }
    }

    private void closeQuietly() {
        try {
            connection.close();
        } catch (SQLException e) {
            ModLog.LOGGER.warn("Failed to close HaveIPlayedWith database", e);
        }
    }
}
