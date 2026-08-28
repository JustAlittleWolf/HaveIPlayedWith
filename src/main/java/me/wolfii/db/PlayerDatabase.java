package me.wolfii.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite store for players you've been around. Each API call is one SQL transaction;
 * WAL + {@code synchronous=NORMAL} lets SQLite batch the actual disk writes instead of
 * the application holding an uncommitted transaction.
 */
public final class PlayerDatabase implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
	public record MojangCache(String username, Instant fetchedAt) {
	}

	public record CraftyCache(String uuid, String currentUsername, String usernamesJson, boolean valid, Instant fetchedAt) {
	}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "haveiplayedwith-db");
		thread.setDaemon(true);
		return thread;
	});
	private final Connection connection;

	public PlayerDatabase(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Class.forName("org.sqlite.JDBC");
			this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA journal_mode=WAL");
				statement.execute("PRAGMA synchronous=NORMAL");
				statement.execute("PRAGMA wal_autocheckpoint=1000");
				statement.execute("PRAGMA foreign_keys=ON");
			}
			connection.setAutoCommit(false);
			createSchema();
			connection.commit();
		} catch (Exception e) {
			throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
		}
	}

	public <T> T call(Callable<T> task) {
		try {
			return worker.submit(() -> {
				try {
					T result = task.call();
					connection.commit();
					return result;
				} catch (Exception e) {
					try {
						connection.rollback();
					} catch (SQLException ignored) {
					}
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
			try (PreparedStatement statement = connection.prepareStatement("UPDATE players SET note = ? WHERE uuid = ?")) {
				if (note == null || note.isBlank()) {
					statement.setNull(1, Types.VARCHAR);
				} else {
					statement.setString(1, note);
				}
				statement.setString(2, uuid.toString());
				statement.executeUpdate();
			} catch (SQLException e) {
				throw new IllegalStateException(e);
			}
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
			if (!existsOnThread(uuid)) {
				return;
			}
			touchUsername(uuid, username, fetchedAt);
			setCurrentUsername(uuid, username);
		});
	}

	public Optional<MojangCache> mojangCache(UUID uuid) {
		return call(() -> {
			try (PreparedStatement statement = connection.prepareStatement(
				"SELECT username, fetched_at FROM mojang_cache WHERE uuid = ?")) {
				statement.setString(1, uuid.toString());
				try (ResultSet rs = statement.executeQuery()) {
					if (!rs.next()) {
						return Optional.empty();
					}
					String username = rs.getString("username");
					return Optional.of(new MojangCache(username, Instant.ofEpochMilli(rs.getLong("fetched_at"))));
				}
			}
		});
	}

	public void putMojangCache(UUID uuid, String username, Instant fetchedAt) {
		run(() -> {
			try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO mojang_cache(uuid, username, fetched_at) VALUES(?, ?, ?) " +
					"ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, fetched_at = excluded.fetched_at")) {
				statement.setString(1, uuid.toString());
				statement.setString(2, username);
				statement.setLong(3, fetchedAt.toEpochMilli());
				statement.executeUpdate();
			} catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	public Optional<CraftyCache> craftyCache(String usernameLower) {
		return call(() -> {
			try (PreparedStatement statement = connection.prepareStatement(
				"SELECT uuid, current_username, usernames_json, valid, fetched_at FROM crafty_cache WHERE username_lower = ?")) {
				statement.setString(1, usernameLower);
				try (ResultSet rs = statement.executeQuery()) {
					if (!rs.next()) {
						return Optional.empty();
					}
					return Optional.of(new CraftyCache(
						rs.getString("uuid"),
						rs.getString("current_username"),
						rs.getString("usernames_json"),
						rs.getInt("valid") != 0,
						Instant.ofEpochMilli(rs.getLong("fetched_at"))
					));
				}
			}
		});
	}

	public void putCraftyCache(String usernameLower, CraftyCache cache) {
		run(() -> {
			try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO crafty_cache(username_lower, uuid, current_username, usernames_json, valid, fetched_at) " +
					"VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT(username_lower) DO UPDATE SET " +
					"uuid = excluded.uuid, current_username = excluded.current_username, " +
					"usernames_json = excluded.usernames_json, valid = excluded.valid, fetched_at = excluded.fetched_at")) {
				statement.setString(1, usernameLower);
				statement.setString(2, cache.uuid());
				statement.setString(3, cache.currentUsername());
				statement.setString(4, cache.usernamesJson());
				statement.setInt(5, cache.valid() ? 1 : 0);
				statement.setLong(6, cache.fetchedAt().toEpochMilli());
				statement.executeUpdate();
			} catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	public Optional<ImportProgress> importProgress(String source) {
		return call(() -> {
			try (PreparedStatement statement = connection.prepareStatement(
				"SELECT processed, total, last_timestamp, skip_count, status FROM import_progress WHERE source = ?")) {
				statement.setString(1, source);
				try (ResultSet rs = statement.executeQuery()) {
					if (!rs.next()) {
						return Optional.empty();
					}
					String timestamp = rs.getString("last_timestamp");
					return Optional.of(new ImportProgress(
						source,
						rs.getLong("processed"),
						rs.getLong("total"),
						timestamp == null ? null : LocalDateTime.parse(timestamp),
						rs.getLong("skip_count"),
						rs.getString("status")
					));
				}
			}
		});
	}

	public void saveImportProgress(ImportProgress progress) {
		run(() -> {
			try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO import_progress(source, processed, total, last_timestamp, skip_count, status) " +
					"VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT(source) DO UPDATE SET " +
					"processed = excluded.processed, total = excluded.total, last_timestamp = excluded.last_timestamp, " +
					"skip_count = excluded.skip_count, status = excluded.status")) {
				statement.setString(1, progress.source());
				statement.setLong(2, progress.processed());
				statement.setLong(3, progress.total());
				if (progress.lastTimestamp() == null) {
					statement.setNull(4, Types.VARCHAR);
				} else {
					statement.setString(4, progress.lastTimestamp().toString());
				}
				statement.setLong(5, progress.skip());
				statement.setString(6, progress.status());
				statement.executeUpdate();
			} catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	@Override
	public void close() {
		try {
			run(() -> {
				try (Statement statement = connection.createStatement()) {
					statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
				}
			});
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to checkpoint HaveIPlayedWith database", e);
		}
		worker.shutdown();
		try {
			if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
				worker.shutdownNow();
			}
			connection.close();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (SQLException e) {
			LOGGER.warn("Failed to close HaveIPlayedWith database", e);
		}
	}

	private void createSchema() throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("""
				CREATE TABLE IF NOT EXISTS players (
					uuid TEXT PRIMARY KEY,
					current_username TEXT NOT NULL,
					note TEXT,
					total_minutes INTEGER NOT NULL DEFAULT 0,
					session_count INTEGER NOT NULL DEFAULT 0
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS username_history (
					uuid TEXT NOT NULL,
					username TEXT NOT NULL,
					last_seen INTEGER NOT NULL,
					PRIMARY KEY (uuid, username COLLATE NOCASE)
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS play_days (
					uuid TEXT NOT NULL,
					day TEXT NOT NULL,
					minutes INTEGER NOT NULL DEFAULT 0,
					PRIMARY KEY (uuid, day)
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS play_sessions (
					uuid TEXT NOT NULL,
					session_id TEXT NOT NULL,
					PRIMARY KEY (uuid, session_id)
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS mojang_cache (
					uuid TEXT PRIMARY KEY,
					username TEXT,
					fetched_at INTEGER NOT NULL
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS crafty_cache (
					username_lower TEXT PRIMARY KEY,
					uuid TEXT,
					current_username TEXT,
					usernames_json TEXT,
					valid INTEGER NOT NULL,
					fetched_at INTEGER NOT NULL
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS import_progress (
					source TEXT PRIMARY KEY,
					processed INTEGER NOT NULL,
					total INTEGER NOT NULL,
					last_timestamp TEXT,
					skip_count INTEGER NOT NULL,
					status TEXT NOT NULL
				)
				""");
			statement.execute("CREATE INDEX IF NOT EXISTS idx_players_username ON players(current_username COLLATE NOCASE)");
			statement.execute("CREATE INDEX IF NOT EXISTS idx_history_username ON username_history(username COLLATE NOCASE)");
		}
	}

	private List<PlayerSnapshot> findByNameOnThread(String name) throws SQLException {
		List<UUID> uuids = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT DISTINCT p.uuid FROM players p
			LEFT JOIN username_history h ON h.uuid = p.uuid
			WHERE p.current_username = ? COLLATE NOCASE
			   OR h.username = ? COLLATE NOCASE
			""")) {
			statement.setString(1, name);
			statement.setString(2, name);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					uuids.add(UUID.fromString(rs.getString("uuid")));
				}
			}
		}
		List<PlayerSnapshot> snapshots = new ArrayList<>();
		for (UUID uuid : uuids) {
			loadSnapshot(uuid).ifPresent(snapshots::add);
		}
		return snapshots;
	}

	private Optional<PlayerSnapshot> loadSnapshot(UUID uuid) throws SQLException {
		String current = null;
		String note = null;
		long minutes = 0;
		int sessions = 0;
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT current_username, note, total_minutes, session_count FROM players WHERE uuid = ?")) {
			statement.setString(1, uuid.toString());
			try (ResultSet rs = statement.executeQuery()) {
				if (!rs.next()) {
					return Optional.empty();
				}
				current = rs.getString("current_username");
				note = rs.getString("note");
				minutes = rs.getLong("total_minutes");
				sessions = rs.getInt("session_count");
			}
		}
		List<SeenName> names = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT username, last_seen FROM username_history WHERE uuid = ? ORDER BY last_seen DESC")) {
			statement.setString(1, uuid.toString());
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					names.add(new SeenName(rs.getString("username"), Instant.ofEpochMilli(rs.getLong("last_seen"))));
				}
			}
		}
		int days = 0;
		try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM play_days WHERE uuid = ?")) {
			statement.setString(1, uuid.toString());
			try (ResultSet rs = statement.executeQuery()) {
				if (rs.next()) {
					days = rs.getInt(1);
				}
			}
		}
		return Optional.of(new PlayerSnapshot(
			uuid,
			current,
			Optional.ofNullable(note).filter(value -> !value.isBlank()),
			minutes,
			sessions,
			days,
			names
		));
	}

	private boolean existsOnThread(UUID uuid) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
			statement.setString(1, uuid.toString());
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next();
			}
		}
	}

	private void ensurePlayerRow(UUID uuid, String username) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"INSERT OR IGNORE INTO players(uuid, current_username) VALUES(?, ?)")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, username);
			statement.executeUpdate();
		}
	}

	private void setCurrentUsername(UUID uuid, String username) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"UPDATE players SET current_username = ? WHERE uuid = ?")) {
			statement.setString(1, username);
			statement.setString(2, uuid.toString());
			statement.executeUpdate();
		}
	}

	private void touchUsername(UUID uuid, String username, Instant seenAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"INSERT INTO username_history(uuid, username, last_seen) VALUES(?, ?, ?) " +
				"ON CONFLICT DO UPDATE SET last_seen = CASE WHEN excluded.last_seen > last_seen THEN excluded.last_seen ELSE last_seen END")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, username);
			statement.setLong(3, seenAt.toEpochMilli());
			statement.executeUpdate();
		}
	}

	private void addSession(UUID uuid, String sessionId) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement(
			"INSERT OR IGNORE INTO play_sessions(uuid, session_id) VALUES(?, ?)")) {
			insert.setString(1, uuid.toString());
			insert.setString(2, sessionId);
			int added = insert.executeUpdate();
			if (added > 0) {
				try (PreparedStatement bump = connection.prepareStatement(
					"UPDATE players SET session_count = session_count + 1 WHERE uuid = ?")) {
					bump.setString(1, uuid.toString());
					bump.executeUpdate();
				}
			}
		}
	}

	private void addMinute(UUID uuid, LocalDate day) throws SQLException {
		ensurePlayDay(uuid, day);
		try (PreparedStatement dayStmt = connection.prepareStatement(
			"UPDATE play_days SET minutes = minutes + 1 WHERE uuid = ? AND day = ?")) {
			dayStmt.setString(1, uuid.toString());
			dayStmt.setString(2, day.toString());
			dayStmt.executeUpdate();
		}
		try (PreparedStatement total = connection.prepareStatement(
			"UPDATE players SET total_minutes = total_minutes + 1 WHERE uuid = ?")) {
			total.setString(1, uuid.toString());
			total.executeUpdate();
		}
	}

	private void ensurePlayDay(UUID uuid, LocalDate day) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"INSERT OR IGNORE INTO play_days(uuid, day, minutes) VALUES(?, ?, 0)")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, day.toString());
			statement.executeUpdate();
		}
	}

	private static RuntimeException unwrap(ExecutionException e) {
		Throwable cause = e.getCause();
		if (cause instanceof RuntimeException runtime) {
			return runtime;
		}
		return new IllegalStateException(cause);
	}
}
