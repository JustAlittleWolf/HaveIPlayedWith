package me.wolfii.haveiplayedwith.store;

import java.time.LocalDateTime;

public record ImportProgress(
    String source,
    long processed,
    long total,
    LocalDateTime lastTimestamp,
    long skip,
    ImportStatus status,
    boolean silenced
) {
    public static final String SOURCE_ALLTHELOGS = "allthelogs";

    public ImportProgress withStatus(ImportStatus status) {
        return new ImportProgress(source, processed, total, lastTimestamp, skip, status, silenced);
    }

    public ImportProgress withSilenced(boolean silenced) {
        return new ImportProgress(source, processed, total, lastTimestamp, skip, status, silenced);
    }

    public ImportProgress withCursor(long processed, LocalDateTime lastTimestamp, long skip) {
        return new ImportProgress(source, processed, total, lastTimestamp, skip, status, silenced);
    }
}
