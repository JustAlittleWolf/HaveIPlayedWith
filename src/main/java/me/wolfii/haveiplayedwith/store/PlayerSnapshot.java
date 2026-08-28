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
    List<ServerPlay> servers
) {
    public PlayerSnapshot {
        names = List.copyOf(names);
        servers = List.copyOf(servers);
    }

    public List<SeenName> pastNames() {
        return names.stream()
            .filter(name -> !name.username().equalsIgnoreCase(currentUsername))
            .toList();
    }

    public Optional<ServerPlay> mostPlayedServer() {
        return servers.isEmpty() ? Optional.empty() : Optional.of(servers.getFirst());
    }

    public boolean hasPlayed() {
        return totalMinutes > 0 || sessionCount > 0 || daysPlayed > 0;
    }
}
