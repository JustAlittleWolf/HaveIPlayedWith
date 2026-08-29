package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record PlayerSnapshot(
    UUID uuid,
    String currentUsername,
    Optional<String> note,
    Optional<Instant> noteTakenAt,
    long totalMinutes,
    int sessionCount,
    int daysPlayed,
    Optional<LocalDate> lastPlayedBeforeToday,
    List<SeenName> names,
    Optional<ServerPlay> mostPlayedServer
) {
    /** Extra minutes required beyond the current session so live logging cannot look like prior play. */
    public static final int CURRENT_SESSION_BUFFER_MINUTES = 5;

    public PlayerSnapshot {
        names = List.copyOf(names);
    }

    public List<SeenName> pastNames() {
        return names.stream()
            .filter(name -> !name.username().equalsIgnoreCase(currentUsername))
            .toList();
    }

    public boolean hasPlayed() {
        return totalMinutes > 0 || sessionCount > 0 || daysPlayed > 0;
    }

    /**
     * True when history is from before this client session. Minutes already logged for
     * {@code currentSessionMinutes} are ignored, plus {@link #CURRENT_SESSION_BUFFER_MINUTES}
     * so calendar-minute ticks cannot flip the answer to yes.
     */
    public boolean hasPlayedBefore(long currentSessionMinutes) {
        long sessionMinutes = Math.max(0L, currentSessionMinutes);
        if (sessionMinutes == 0L) {
            return hasPlayed();
        }
        if (totalMinutes > sessionMinutes + CURRENT_SESSION_BUFFER_MINUTES) {
            return true;
        }
        return sessionCount > 1;
    }
}
