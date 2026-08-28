package me.wolfii.haveiplayedwith.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Opened SmallSQL connection plus the single worker thread that may touch it.
 */
final class StoreSession implements AutoCloseable {
    private static final String DRIVER = "smallsql.database.SSDriver";
    private final StoreWorker worker;
    final StoreDb db;

    private StoreSession(StoreWorker worker, StoreDb db) {
        this.worker = worker;
        this.db = db;
    }

    static StoreSession open(Path directory) {
        try {
            Files.createDirectories(directory.getParent());
            Class.forName(DRIVER);
            Properties properties = new Properties();
            properties.setProperty("create", "true");
            String url = "jdbc:smallsql:" + directory.toAbsolutePath();
            Connection connection = DriverManager.getConnection(url, properties);
            connection.setAutoCommit(false);
            StoreDb db = new StoreDb(connection);
            db.createTables();
            connection.commit();
            return new StoreSession(new StoreWorker(connection), db);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + directory, e);
        }
    }

    <T> T call(Callable<T> task) {
        return worker.call(task);
    }

    void run(StoreWork task) {
        worker.run(task);
    }

    @Override
    public void close() {
        worker.close();
    }
}
