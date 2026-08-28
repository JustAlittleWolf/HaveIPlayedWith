package me.wolfii.haveiplayedwith.store;

/**
 * JSON shapes stored in MVStore maps. Field names are part of the on-disk format.
 */
final class StoreRows {
    private StoreRows() {
    }

    record PlayerRow(String currentUsername, String note, Long noteTakenAt, long totalMinutes, int sessionCount) {
        PlayerRow withUsername(String username) {
            return new PlayerRow(username, note, noteTakenAt, totalMinutes, sessionCount);
        }

        PlayerRow withNote(String note, Long noteTakenAt) {
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
    }

    record InstantRow(String username, long fetchedAt) {
    }

    record NameCacheRow(String uuid, String username, long fetchedAt) {
    }

    record CraftyRow(String uuid, String currentUsername, String usernamesJson, boolean valid, long fetchedAt) {
    }

    record ImportRow(long processed, long total, String lastTimestamp, long skip, String status, boolean silenced) {
    }
}
