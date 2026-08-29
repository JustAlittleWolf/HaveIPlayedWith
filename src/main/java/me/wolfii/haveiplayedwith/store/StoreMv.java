package me.wolfii.haveiplayedwith.store;

import me.wolfii.haveiplayedwith.ModLog;
import org.dizitart.no2.Nitrite;
import org.h2.mvstore.FileStore;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreTool;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * H2 MVStore knobs and rewrite-compact. Nitrite opens the store with
 * {@code autoCompactFillRate(0)} because H2's background compact can race with
 * writes; this class never turns that back on. Instead it copies live pages into
 * a new file, and only when the fill rate says that copy would actually shrink
 * the file.
 *
 * <p>RocksDB and sqlite-jdbc would keep the on-disk file smaller on their own,
 * but both blow past the 1 MB mod jar budget. The live player data is small;
 * the ballooning is leftover MVStore chunks, which a rewrite reclaims.
 */
final class StoreMv {
    /** Skip compact when the file is still a reasonable size. */
    static final long MIN_FILE_BYTES = 1024L * 1024L;
    /** Skip compact unless at least this many bytes would be reclaimed. */
    static final long MIN_RECLAIM_BYTES = 1024L * 1024L;
    /** Skip compact when chunks are already at least this percent live. */
    static final int MAX_LIVE_PERCENT = 50;
    /**
     * Nitrite turns auto-commit on at 1s after the first map is created. A longer
     * delay means fewer copy-on-write chunks for the same minute ticks.
     */
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

    static void tune(Nitrite nitrite) {
        MVStore store = unwrap(nitrite);
        if (store != null) {
            store.setAutoCommitDelay(AUTO_COMMIT_DELAY_MS);
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

    static boolean shouldCompact(Path file) {
        long fileBytes = size(file);
        if (fileBytes < MIN_FILE_BYTES) {
            return false;
        }
        try (MVStore store = new MVStore.Builder().fileName(path(file)).readOnly().open()) {
            return worthRewriting(fileBytes, livePercent(store));
        } catch (Exception e) {
            ModLog.LOGGER.debug("Could not inspect HaveIPlayedWith store for compact", e);
            return false;
        }
    }

    static boolean shouldCompact(Path file, Nitrite nitrite) {
        long fileBytes = size(file);
        if (fileBytes < MIN_FILE_BYTES) {
            return false;
        }
        MVStore store = unwrap(nitrite);
        if (store == null || store.isClosed()) {
            return false;
        }
        return worthRewriting(fileBytes, livePercent(store));
    }

    static void compact(Path file) {
        MVStoreTool.compact(path(file), true);
    }

    static void compactIfWorthwhile(Path file) {
        cleanup(file);
        if (!shouldCompact(file)) {
            return;
        }
        long before = size(file);
        compact(file);
        cleanup(file);
        long after = size(file);
        if (after < before) {
            ModLog.LOGGER.info("Compacted HaveIPlayedWith store from {} to {} bytes", before, after);
        }
    }

    static int livePercent(MVStore store) {
        FileStore<?> files = store.getFileStore();
        if (files == null) {
            return 100;
        }
        return Math.min(files.getFillRate(), files.getChunksFillRate());
    }

    static MVStore unwrap(Nitrite nitrite) {
        try {
            Object store = nitrite.getStore();
            Field field = store.getClass().getDeclaredField("mvStore");
            field.setAccessible(true);
            return (MVStore) field.get(store);
        } catch (ReflectiveOperationException e) {
            ModLog.LOGGER.debug("Could not reach the MVStore under Nitrite", e);
            return null;
        }
    }
}
