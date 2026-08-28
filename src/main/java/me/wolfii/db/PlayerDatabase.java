package me.wolfii.db;

import com.google.gson.Gson;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Single-file store for players you've been around, backed by H2 MVStore (pure Java, ~350 KB).
 * Each logical write is committed from the database thread, not the client thread.
 */
public final class PlayerDatabase implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
	private static final Gson GSON = new Gson();

	public record MojangCache(String username, Instant fetchedAt) {
	}

	public record MojangNameCache(UUID uuid, String username, Instant fetchedAt) {
	}

	public record CraftyCache(String uuid, String currentUsername, String usernamesJson, boolean valid, Instant fetchedAt) {
	}

	private record PlayerRow(String currentUsername, String note, long totalMinutes, int sessionCount) {
	}

	private record HistoryRow(String username, long lastSeen) {
	}

	private record InstantRow(String username, long fetchedAt) {
	}

	private record NameCacheRow(String uuid, String username, long fetchedAt) {
	}

	private record CraftyRow(String uuid, String currentUsername, String usernamesJson, boolean valid, long fetchedAt) {
	}

	private record ImportRow(long processed, long total, String lastTimestamp, long skip, String status, boolean silenced) {
	}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "haveiplayedwith-db");
		thread.setDaemon(true);
		return thread;
	});
	private final MVStore store;
	private final MVMap<String, String> players;
	private final MVMap<String, String> history;
	private final MVMap<String, String> nameIndex;
	private final MVMap<String, String> playDays;
	private final MVMap<String, String> playSessions;
	private final MVMap<String, String> mojangUuid;
	private final MVMap<String, String> mojangName;
	private final MVMap<String, String> crafty;
	private final MVMap<String, String> imports;

	public PlayerDatabase(Path file) {
		try {
			Files.createDirectories(file.getParent());
			this.store = new MVStore.Builder()
				.fileName(file.toAbsolutePath().toString())
				.compress()
				.autoCommitDisabled()
				.open();
			this.players = store.openMap("players");
			this.history = store.openMap("username_history");
			this.nameIndex = store.openMap("name_index");
			this.playDays = store.openMap("play_days");
			this.playSessions = store.openMap("play_sessions");
			this.mojangUuid = store.openMap("mojang_uuid");
			this.mojangName = store.openMap("mojang_name");
			this.crafty = store.openMap("crafty");
			this.imports = store.openMap("import_progress");
		} catch (Exception e) {
			throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
		}
	}

	public <T> T call(Callable<T> task) {
		try {
			return worker.submit(() -> {
				try {
					T result = task.call();
					store.commit();
					return result;
				} catch (Exception e) {
					store.rollback();
					throw e;
				}
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		} catch (ExecutionException e) {
			throw unwrap(e);
		}
	}

	public void run(DbWork task) {
		call(() -> {
			task.run();
			return null;
		});
	}

	@FunctionalInterface
	public interface DbWork {
		void run() throws Exception;
	}

	public List<PlayerSnapshot> findByName(String name) {
		return call(() -> findByNameOnThread(name));
	}

	public Optional<PlayerSnapshot> get(UUID uuid) {
		return call(() -> loadSnapshot(uuid));
	}

	public void setNote(UUID uuid, String username, String note) {
		run(() -> {
			ensurePlayerRow(uuid, username);
			PlayerRow row = playerRow(uuid);
			putPlayer(uuid, new PlayerRow(row.currentUsername(), blankToNull(note), row.totalMinutes(), row.sessionCount()));
		});
	}

	public void recordLivePlay(UUID uuid, String username, LocalDate day, String sessionId) {
		run(() -> {
			Instant now = Instant.now();
			ensurePlayerRow(uuid, username);
			touchUsername(uuid, username, now);
			setCurrentUsername(uuid, username);
			addSession(uuid, sessionId);
			addMinute(uuid, day);
		});
	}

	/**
	 * Only {@code seenUsername} enters the name history: the name Crafty reports as current was
	 * never actually seen by this player, so it gets no "last seen" timestamp.
	 */
	public void recordImportedSighting(UUID uuid, String seenUsername, String currentUsername, LocalDate day, String sessionId, Instant seenAt) {
		run(() -> {
			String display = currentUsername == null || currentUsername.isBlank() ? seenUsername : currentUsername;
			ensurePlayerRow(uuid, display);
			if (currentUsername != null && !currentUsername.isBlank()) {
				setCurrentUsername(uuid, currentUsername);
			}
			touchUsername(uuid, seenUsername, seenAt);
			addSession(uuid, sessionId);
			ensurePlayDay(uuid, day);
		});
	}

	public void applyMojangUsername(UUID uuid, String username, Instant fetchedAt) {
		run(() -> {
			if (playerRow(uuid) == null) {
				return;
			}
			touchUsername(uuid, username, fetchedAt);
			setCurrentUsername(uuid, username);
		});
	}

	public Optional<MojangCache> mojangCache(UUID uuid) {
		return call(() -> {
			String raw = mojangUuid.get(uuid.toString());
			if (raw == null) {
				return Optional.empty();
			}
			InstantRow row = GSON.fromJson(raw, InstantRow.class);
			return Optional.of(new MojangCache(row.username(), Instant.ofEpochMilli(row.fetchedAt())));
		});
	}

	public void putMojangCache(UUID uuid, String username, Instant fetchedAt) {
		run(() -> mojangUuid.put(uuid.toString(), GSON.toJson(new InstantRow(username, fetchedAt.toEpochMilli()))));
	}

	public Optional<MojangNameCache> mojangNameCache(String usernameLower) {
		return call(() -> {
			String raw = mojangName.get(usernameLower);
			if (raw == null) {
				return Optional.empty();
			}
			NameCacheRow row = GSON.fromJson(raw, NameCacheRow.class);
			UUID uuid = row.uuid() == null ? null : UUID.fromString(row.uuid());
			return Optional.of(new MojangNameCache(uuid, row.username(), Instant.ofEpochMilli(row.fetchedAt())));
		});
	}

	public void putMojangNameCache(String usernameLower, MojangNameCache cache) {
		run(() -> mojangName.put(usernameLower, GSON.toJson(new NameCacheRow(
			cache.uuid() == null ? null : cache.uuid().toString(),
			cache.username(),
			cache.fetchedAt().toEpochMilli()
		))));
	}

	public Optional<CraftyCache> craftyCache(String usernameLower) {
		return call(() -> {
			String raw = crafty.get(usernameLower);
			if (raw == null) {
				return Optional.empty();
			}
			CraftyRow row = GSON.fromJson(raw, CraftyRow.class);
			return Optional.of(new CraftyCache(
				row.uuid(),
				row.currentUsername(),
				row.usernamesJson(),
				row.valid(),
				Instant.ofEpochMilli(row.fetchedAt())
			));
		});
	}

	public void putCraftyCache(String usernameLower, CraftyCache cache) {
		run(() -> crafty.put(usernameLower, GSON.toJson(new CraftyRow(
			cache.uuid(),
			cache.currentUsername(),
			cache.usernamesJson(),
			cache.valid(),
			cache.fetchedAt().toEpochMilli()
		))));
	}

	public Optional<ImportProgress> importProgress(String source) {
		return call(() -> {
			String raw = imports.get(source);
			if (raw == null) {
				return Optional.empty();
			}
			ImportRow row = GSON.fromJson(raw, ImportRow.class);
			return Optional.of(new ImportProgress(
				source,
				row.processed(),
				row.total(),
				row.lastTimestamp() == null ? null : LocalDateTime.parse(row.lastTimestamp()),
				row.skip(),
				row.status(),
				row.silenced()
			));
		});
	}

	public void saveImportProgress(ImportProgress progress) {
		run(() -> imports.put(progress.source(), GSON.toJson(new ImportRow(
			progress.processed(),
			progress.total(),
			progress.lastTimestamp() == null ? null : progress.lastTimestamp().toString(),
			progress.skip(),
			progress.status(),
			progress.silenced()
		))));
	}

	@Override
	public void close() {
		try {
			run(store::commit);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to commit HaveIPlayedWith database", e);
		}
		worker.shutdown();
		try {
			if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
				worker.shutdownNow();
			}
			store.close();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			store.closeImmediately();
		}
	}

	private List<PlayerSnapshot> findByNameOnThread(String name) {
		String raw = nameIndex.get(name.toLowerCase(Locale.ROOT));
		if (raw == null) {
			return List.of();
		}
		List<PlayerSnapshot> snapshots = new ArrayList<>();
		for (String id : GSON.fromJson(raw, String[].class)) {
			loadSnapshot(UUID.fromString(id)).ifPresent(snapshots::add);
		}
		return snapshots;
	}

	private Optional<PlayerSnapshot> loadSnapshot(UUID uuid) {
		PlayerRow row = playerRow(uuid);
		if (row == null) {
			return Optional.empty();
		}
		List<SeenName> names = new ArrayList<>();
		String prefix = uuid + "\t";
		Iterator<String> keys = history.keyIterator(prefix);
		while (keys.hasNext()) {
			String key = keys.next();
			if (!key.startsWith(prefix)) {
				break;
			}
			HistoryRow seen = GSON.fromJson(history.get(key), HistoryRow.class);
			names.add(new SeenName(seen.username(), Instant.ofEpochMilli(seen.lastSeen())));
		}
		names.sort(Comparator.comparing(SeenName::lastSeen).reversed());
		return Optional.of(new PlayerSnapshot(
			uuid,
			row.currentUsername(),
			Optional.ofNullable(row.note()).filter(value -> !value.isBlank()),
			row.totalMinutes(),
			row.sessionCount(),
			countPrefix(playDays, prefix),
			names
		));
	}

	private PlayerRow playerRow(UUID uuid) {
		String raw = players.get(uuid.toString());
		return raw == null ? null : GSON.fromJson(raw, PlayerRow.class);
	}

	private void putPlayer(UUID uuid, PlayerRow row) {
		players.put(uuid.toString(), GSON.toJson(row));
	}

	private void ensurePlayerRow(UUID uuid, String username) {
		if (playerRow(uuid) != null) {
			return;
		}
		putPlayer(uuid, new PlayerRow(username, null, 0, 0));
		indexName(uuid, username);
	}

	private void setCurrentUsername(UUID uuid, String username) {
		PlayerRow row = playerRow(uuid);
		if (row == null) {
			return;
		}
		putPlayer(uuid, new PlayerRow(username, row.note(), row.totalMinutes(), row.sessionCount()));
		indexName(uuid, username);
	}

	private void touchUsername(UUID uuid, String username, Instant seenAt) {
		String key = uuid + "\t" + username.toLowerCase(Locale.ROOT);
		String raw = history.get(key);
		long millis = seenAt.toEpochMilli();
		if (raw != null) {
			HistoryRow existing = GSON.fromJson(raw, HistoryRow.class);
			if (existing.lastSeen() >= millis) {
				indexName(uuid, existing.username());
				return;
			}
		}
		history.put(key, GSON.toJson(new HistoryRow(username, millis)));
		indexName(uuid, username);
	}

	private void indexName(UUID uuid, String username) {
		String key = username.toLowerCase(Locale.ROOT);
		String id = uuid.toString();
		String raw = nameIndex.get(key);
		List<String> ids = raw == null ? new ArrayList<>() : new ArrayList<>(List.of(GSON.fromJson(raw, String[].class)));
		if (!ids.contains(id)) {
			ids.add(id);
			nameIndex.put(key, GSON.toJson(ids));
		}
	}

	private void addSession(UUID uuid, String sessionId) {
		String key = uuid + "\t" + sessionId;
		if (playSessions.containsKey(key)) {
			return;
		}
		playSessions.put(key, "1");
		PlayerRow row = playerRow(uuid);
		putPlayer(uuid, new PlayerRow(row.currentUsername(), row.note(), row.totalMinutes(), row.sessionCount() + 1));
	}

	private void addMinute(UUID uuid, LocalDate day) {
		ensurePlayDay(uuid, day);
		String key = uuid + "\t" + day;
		long minutes = Long.parseLong(playDays.get(key)) + 1;
		playDays.put(key, Long.toString(minutes));
		PlayerRow row = playerRow(uuid);
		putPlayer(uuid, new PlayerRow(row.currentUsername(), row.note(), row.totalMinutes() + 1, row.sessionCount()));
	}

	private void ensurePlayDay(UUID uuid, LocalDate day) {
		playDays.putIfAbsent(uuid + "\t" + day, "0");
	}

	private static int countPrefix(MVMap<String, String> map, String prefix) {
		int count = 0;
		Iterator<String> keys = map.keyIterator(prefix);
		while (keys.hasNext()) {
			String key = keys.next();
			if (!key.startsWith(prefix)) {
				break;
			}
			count++;
		}
		return count;
	}

	private static String blankToNull(String note) {
		return note == null || note.isBlank() ? null : note;
	}

	private static RuntimeException unwrap(ExecutionException e) {
		Throwable cause = e.getCause();
		if (cause instanceof RuntimeException runtime) {
			return runtime;
		}
		return new IllegalStateException(cause);
	}
}
