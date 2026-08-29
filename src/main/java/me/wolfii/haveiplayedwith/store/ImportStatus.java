package me.wolfii.haveiplayedwith.store;

import java.util.Locale;

/** How far an import got. Persisted as the lowercase constant name. */
public enum ImportStatus {
    /** Interrupted part way through, and picked up again on the next launch. */
    RUNNING,
    /** Stopped on request. The stored cursor still says where to resume from. */
    STOPPED,
    /** Every log line was read. Starting again begins from scratch. */
    DONE;

    /** Anything unrecognised reads back as {@link #STOPPED}, so a damaged row never resumes by itself. */
    public static ImportStatus fromStorage(String stored) {
        for (ImportStatus status : values()) {
            if (status.storageName().equalsIgnoreCase(stored)) {
                return status;
            }
        }
        return STOPPED;
    }

    public String storageName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
