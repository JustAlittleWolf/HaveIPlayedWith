package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreTool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens the MVStore file. H2 auto-commits and auto-compacts on its writer
 * thread; this class does not rewrite the file.
 */
final class StoreMv {
    /** Batches a tab-list burst into one copy-on-write generation. */
    static final int AUTO_COMMIT_DELAY_MS = 10_000;
    /** H2 default. Sparse leftover chunks are reclaimed on the auto-commit thread. */
    static final int AUTO_COMPACT_FILL_RATE = 50;

    private StoreMv() {
    }

    static String path(Path file) {
        return file.toAbsolutePath().toString();
    }

    static long size(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    static void cleanup(Path file) {
        MVStoreTool.compactCleanUp(path(file));
    }

    static MVStore open(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            cleanup(file);
            MVStore store = openStore(file);
            if (legacyNitrite(store)) {
                store.closeImmediately();
                Files.deleteIfExists(file);
                cleanup(file);
                ModLog.LOGGER.info("Discarded incompatible HaveIPlayedWith store at {}", file);
                store = openStore(file);
            }
            store.setAutoCommitDelay(AUTO_COMMIT_DELAY_MS);
            return store;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith store at " + file, e);
        }
    }

    private static MVStore openStore(Path file) {
        return new MVStore.Builder()
            .fileName(path(file))
            .compressHigh()
            .autoCommitBufferSize(2048)
            .autoCompactFillRate(AUTO_COMPACT_FILL_RATE)
            .open();
    }

    static boolean legacyNitrite(MVStore store) {
        return store.hasMap("$nitrite_catalog")
            || store.hasMap("username_history")
            || store.hasMap("play_days")
            || store.hasMap("play_sessions")
            || store.hasMap("play_servers");
    }
}