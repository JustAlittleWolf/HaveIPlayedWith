package me.wolfii.haveiplayedwith.store;

import java.util.Objects;

/**
 * Row shapes persisted in SmallSQL. String fields are never stored as SQL NULL:
 * absent values are empty strings, and {@code noteTakenAt} uses {@code 0} when there
 * is no note.
 */
final class StoreRows {
    private StoreRows() {
    }

    record PlayerRow(String currentUsername, String note, long noteTakenAt, long totalMinutes, int sessionCount) {
        PlayerRow {
            currentUsername = requireText(currentUsername);
            note = emptyIfNull(note);
        }

        PlayerRow withUsername(String username) {
            return new PlayerRow(username, note, noteTakenAt, totalMinutes, sessionCount);
        }

        PlayerRow withNote(String note, long noteTakenAt) {
            return new PlayerRow(currentUsername, note, noteTakenAt, totalMinutes, sessionCount);
        }

        PlayerRow plusMinute() {
            return new PlayerRow(currentUsername, note, noteTakenAt, totalMinutes + 1, sessionCount);
        }

        PlayerRow plusSession() {
            return new PlayerRow(currentUsername, note, noteTakenAt, totalMinutes, sessionCount + 1);
        }
    }

    record HistoryRow(String username, long lastSeen) {
        HistoryRow {
            username = requireText(username);
        }
    }

    record MojangUuidRow(String username, long fetchedAt) {
        MojangUuidRow {
            username = emptyIfNull(username);
        }
    }

    record MojangNameRow(String uuid, String username, long fetchedAt) {
        MojangNameRow {
            uuid = emptyIfNull(uuid);
            username = emptyIfNull(username);
        }
    }

    record ImportRow(long processed, long total, String lastTimestamp, long skip, String status, boolean silenced) {
        ImportRow {
            lastTimestamp = emptyIfNull(lastTimestamp);
            status = requireText(status);
        }
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("stored text must not be blank");
        }
        return value;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
