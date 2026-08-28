package me.wolfii.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record PlayerSnapshot(
	UUID uuid,
	String currentUsername,
	Optional<String> note,
	long totalMinutes,
	int sessionCount,
	int daysPlayed,
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

	public boolean hasPlayed() {
		return totalMinutes > 0 || sessionCount > 0 || daysPlayed > 0;
	}
}
