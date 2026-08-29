package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import org.h2.mvstore.FileStore;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreTool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * H2 MVStore knobs and rewrite-compact. Background compact is left off
 * ({@code autoCompactFillRate(0)}) because it can race with writes. Leftover
 * chunks are copied into a new file only when in-memory fill stats say that
 * rewrite would actually shrink the file.
 */
final class StoreMv {
    /** Skip compact when the file is still a reasonable size. */
    static final long MIN_FILE_BYTES = 1024L * 1024L;
    /** Skip compact unless at least this many bytes would be reclaimed. */
    static final long MIN_RECLAIM_BYTES = 1024L * 1024L;
    /** Skip compact when chunks are already at least this percent live. */
    static final int MAX_LIVE_PERCENT = 50;
    /** Longer delay means fewer copy-on-write chunks for the same dirty maps. */
    static final int AUTO_COMMIT_DELAY_MS = 10_000;

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
            MVStore store = new MVStore.Builder()
                .fileName(path(file))
                .compressHigh()
                .autoCommitBufferSize(2048)
                .autoCompactFillRate(0)
                .open();
            store.setAutoCommitDelay(AUTO_COMMIT_DELAY_MS);
            return store;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith store at " + file, e);
        }
    }

    /**
     * True when a rewrite would drop at least {@link #MIN_RECLAIM_BYTES} and the
     * file is already larger than {@link #MIN_FILE_BYTES}.
     */
    static boolean worthRewriting(long fileBytes, int livePercent) {
        if (fileBytes < MIN_FILE_BYTES) {
            return false;
        }
        int live = Math.max(0, Math.min(livePercent, 100));
        long reclaimable = fileBytes - fileBytes * live / 100L;
        return reclaimable >= MIN_RECLAIM_BYTES && live <= MAX_LIVE_PERCENT;
    }

    static boolean shouldCompact(MVStore store) {
        if (store == null || store.isClosed()) {
            return false;
        }
        FileStore<?> files = store.getFileStore();
        if (files == null) {
            return false;
        }
        return worthRewriting(files.size(), livePercent(files));
    }

    static boolean shouldCompact(Path file) {
        long fileBytes = size(file);
        if (fileBytes < MIN_FILE_BYTES) {
            return false;
        }
        try (MVStore store = new MVStore.Builder().fileName(path(file)).readOnly().open()) {
            return worthRewriting(fileBytes, livePercent(store.getFileStore()));
        } catch (Exception e) {
            ModLog.LOGGER.debug("Could not inspect HaveIPlayedWith store for compact", e);
            return false;
        }
    }

    static void compact(Path file) {
        MVStoreTool.compact(path(file), true);
    }

    static int livePercent(FileStore<?> files) {
        if (files == null) {
            return 100;
        }
        return Math.min(files.getFillRate(), files.getChunksFillRate());
    }
}