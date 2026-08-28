package me.wolfii.importing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Crafty returns name history newest-first. A name is held from its {@code changed_at}
 * (or the beginning of time when that is null) until the next newer change.
 */
public final class CraftyNameHistory {
	public record Entry(String username, Instant changedAt) {
	}

	private CraftyNameHistory() {
	}

	public static boolean heldNameAt(List<Entry> history, String username, Instant at) {
		if (username == null || at == null) {
			return false;
		}
		List<Entry> sorted = new ArrayList<>(history);
		sorted.sort(Comparator.comparing(entry -> entry.changedAt() == null ? Instant.EPOCH : entry.changedAt()));
		for (int i = 0; i < sorted.size(); i++) {
			Entry entry = sorted.get(i);
			if (!username.equalsIgnoreCase(entry.username())) {
				continue;
			}
			Instant from = entry.changedAt() == null ? Instant.EPOCH : entry.changedAt();
			Instant to = i + 1 < sorted.size() && sorted.get(i + 1).changedAt() != null
				? sorted.get(i + 1).changedAt()
				: Instant.MAX;
			if (!at.isBefore(from) && at.isBefore(to)) {
				return true;
			}
		}
		return false;
	}

	public static Instant parseCraftyTime(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException ignored) {
			try {
				return Instant.parse(value);
			} catch (DateTimeParseException ignoredAgain) {
				return null;
			}
		}
	}

	public static String cacheKey(String username) {
		return username.toLowerCase(Locale.ROOT);
	}
}
