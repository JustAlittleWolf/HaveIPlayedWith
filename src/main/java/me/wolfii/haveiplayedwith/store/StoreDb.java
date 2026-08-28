package me.wolfii.haveiplayedwith.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * SmallSQL connection and queries, over the tables {@link StoreSchema} defines. String
 * columns store empty strings instead of SQL NULL. All access goes through
 * {@link StoreWorker}.
 */
final class StoreDb implements AutoCloseable {
    private static final String DRIVER = "smallsql.database.SSDriver";

    private final StoreWorker worker;
    private final Connection connection;

    private StoreDb(StoreWorker worker, Connection connection) {
        this.worker = worker;
        this.connection = connection;
    }

    static StoreDb open(Path directory) {
        try {
            Files.createDirectories(directory.getParent());
            Class.forName(DRIVER);
            Properties properties = new Properties();
            properties.setProperty("create", "true");
            Connection connection = DriverManager.getConnection("jdbc:smallsql:" + directory.toAbsolutePath(), properties);
            connection.setAutoCommit(false);
            StoreDb db = new StoreDb(new StoreWorker(connection), connection);
            StoreSchema.create(connection);
            connection.commit();
            return db;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + directory, e);
        }
    }

    <T> T call(Callable<T> task) {
        return worker.call(task);
    }

    void run(StoreWork task) {
        worker.run(task);
    }

    @Override
    public void close() {
        worker.close();
    }

    boolean hasPlayer(UUID uuid) {
        return exists("SELECT player_uuid FROM players WHERE player_uuid = ?", id(uuid));
    }

    void ensurePlayer(UUID uuid, String username) {
        if (hasPlayer(uuid)) {
            return;
        }
        execute(
            "INSERT INTO players (player_uuid, current_username, note, note_taken_at, total_minutes, session_count) VALUES (?,?,?,?,?,?)",
            id(uuid), username, "", 0L, 0L, 0
        );
        indexName(uuid, username);
    }

    void setNote(UUID uuid, String note, long noteTakenAt) {
        execute("UPDATE players SET note = ?, note_taken_at = ? WHERE player_uuid = ?", note, noteTakenAt, id(uuid));
    }

    void setCurrentUsername(UUID uuid, String username) {
        execute("UPDATE players SET current_username = ? WHERE player_uuid = ?", username, id(uuid));
        indexName(uuid, username);
    }

    void touchUsername(UUID uuid, String username, Instant seenAt) {
        long millis = seenAt.toEpochMilli();
        Long lastSeen = queryOne(
            "SELECT last_seen FROM username_history WHERE player_uuid = ? AND username_lower = ?",
            rs -> rs.getLong(1),
            id(uuid),
            lower(username)
        );
        if (lastSeen != null && lastSeen >= millis) {
            indexName(uuid, username);
            return;
        }
        upsert(
            "UPDATE username_history SET username = ?, last_seen = ? WHERE player_uuid = ? AND username_lower = ?",
            List.of(username, millis, id(uuid), lower(username)),
            "INSERT INTO username_history (player_uuid, username_lower, username, last_seen) VALUES (?,?,?,?)",
            List.of(id(uuid), lower(username), username, millis)
        );
        indexName(uuid, username);
    }

    Optional<String> previousSeenNameIfDifferent(UUID uuid, String username) {
        List<SeenName> names = listHistory(uuid);
        if (names.isEmpty() || names.getFirst().username().equalsIgnoreCase(username)) {
            return Optional.empty();
        }
        return Optional.of(names.getFirst().username());
    }

    List<PlayerSnapshot> findByName(String name) {
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (UUID uuid : queryAll(
            "SELECT player_uuid FROM name_index WHERE username_lower = ?",
            rs -> UUID.fromString(rs.getString(1)),
            lower(name)
        )) {
            snapshot(uuid).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    Optional<PlayerSnapshot> snapshot(UUID uuid) {
        PlayerFields row = queryOne(
            "SELECT current_username, note, note_taken_at, total_minutes, session_count FROM players WHERE player_uuid = ?",
            rs -> new PlayerFields(
                rs.getString(1),
                emptyIfNull(rs.getString(2)),
                rs.getLong(3),
                rs.getLong(4),
                rs.getInt(5)
            ),
            id(uuid)
        );
        if (row == null) {
            return Optional.empty();
        }
        Optional<String> note = Optional.of(row.note()).filter(value -> !value.isBlank());
        Optional<Instant> noteTakenAt = note.isEmpty() || row.noteTakenAt() == 0L
            ? Optional.empty()
            : Optional.of(Instant.ofEpochMilli(row.noteTakenAt()));
        return Optional.of(new PlayerSnapshot(
            uuid,
            row.currentUsername(),
            note,
            noteTakenAt,
            row.totalMinutes(),
            row.sessionCount(),
            countPlayDays(uuid),
            lastPlayedBefore(uuid, LocalDate.now()),
            listHistory(uuid),
            listServers(uuid)
        ));
    }

    Long sessionMinutes(UUID uuid, String sessionId) {
        return queryOne(
            "SELECT minutes FROM play_sessions WHERE player_uuid = ? AND session_id = ?",
            rs -> rs.getLong(1),
            id(uuid),
            sessionId
        );
    }

    void addSession(UUID uuid, String sessionId) {
        if (sessionMinutes(uuid, sessionId) != null) {
            return;
        }
        execute("INSERT INTO play_sessions (player_uuid, session_id, minutes) VALUES (?,?,?)", id(uuid), sessionId, 0L);
        execute("UPDATE players SET session_count = session_count + 1 WHERE player_uuid = ?", id(uuid));
    }

    void addSessionMinute(UUID uuid, String sessionId) {
        if (execute("UPDATE play_sessions SET minutes = minutes + 1 WHERE player_uuid = ? AND session_id = ?", id(uuid), sessionId) > 0) {
            return;
        }
        execute("INSERT INTO play_sessions (player_uuid, session_id, minutes) VALUES (?,?,?)", id(uuid), sessionId, 1L);
        execute("UPDATE players SET session_count = session_count + 1 WHERE player_uuid = ?", id(uuid));
    }

    void addMinute(UUID uuid, LocalDate day, String serverId) {
        if (execute("UPDATE play_days SET minutes = minutes + 1 WHERE player_uuid = ? AND play_day = ?", id(uuid), day.toString()) == 0) {
            execute("INSERT INTO play_days (player_uuid, play_day, minutes) VALUES (?,?,?)", id(uuid), day.toString(), 1L);
        }
        addServerMinute(uuid, serverId);
        execute("UPDATE players SET total_minutes = total_minutes + 1 WHERE player_uuid = ?", id(uuid));
    }

    void ensurePlayDay(UUID uuid, LocalDate day) {
        if (exists("SELECT player_uuid FROM play_days WHERE player_uuid = ? AND play_day = ?", id(uuid), day.toString())) {
            return;
        }
        execute("INSERT INTO play_days (player_uuid, play_day, minutes) VALUES (?,?,?)", id(uuid), day.toString(), 0L);
    }

    Optional<MojangUuidCache> mojangUuid(UUID uuid) {
        return Optional.ofNullable(queryOne(
            "SELECT username, fetched_at FROM mojang_uuid WHERE player_uuid = ?",
            rs -> new MojangUuidCache(emptyIfNull(rs.getString(1)), Instant.ofEpochMilli(rs.getLong(2))),
            id(uuid)
        ));
    }

    void putMojangUuid(UUID uuid, String username, Instant fetchedAt) {
        String stored = username == null ? "" : username;
        upsert(
            "UPDATE mojang_uuid SET username = ?, fetched_at = ? WHERE player_uuid = ?",
            List.of(stored, fetchedAt.toEpochMilli(), id(uuid)),
            "INSERT INTO mojang_uuid (player_uuid, username, fetched_at) VALUES (?,?,?)",
            List.of(id(uuid), stored, fetchedAt.toEpochMilli())
        );
    }

    Optional<MojangNameCache> mojangName(String usernameLower) {
        return Optional.ofNullable(queryOne(
            "SELECT player_uuid, username, fetched_at FROM mojang_name WHERE username_lower = ?",
            rs -> {
                String rawUuid = emptyIfNull(rs.getString(1));
                String rawName = emptyIfNull(rs.getString(2));
                return new MojangNameCache(
                    rawUuid.isBlank() ? null : UUID.fromString(rawUuid),
                    rawName.isBlank() ? null : rawName,
                    Instant.ofEpochMilli(rs.getLong(3))
                );
            },
            usernameLower
        ));
    }

    void putMojangName(String usernameLower, MojangNameCache cache) {
        String storedUuid = cache.uuid() == null ? "" : cache.uuid().toString();
        String storedName = cache.username() == null ? "" : cache.username();
        upsert(
            "UPDATE mojang_name SET player_uuid = ?, username = ?, fetched_at = ? WHERE username_lower = ?",
            List.of(storedUuid, storedName, cache.fetchedAt().toEpochMilli(), usernameLower),
            "INSERT INTO mojang_name (username_lower, player_uuid, username, fetched_at) VALUES (?,?,?,?)",
            List.of(usernameLower, storedUuid, storedName, cache.fetchedAt().toEpochMilli())
        );
    }

    void putMojangCurrent(UUID uuid, String username, Instant fetchedAt) {
        putMojangUuid(uuid, username, fetchedAt);
        putMojangName(username.toLowerCase(Locale.ROOT), new MojangNameCache(uuid, username, fetchedAt));
    }

    Optional<ImportProgress> importProgress(String source) {
        return Optional.ofNullable(queryOne(
            "SELECT processed, total, last_timestamp, skip_count, status, silenced FROM import_progress WHERE source_id = ?",
            rs -> {
                String lastTimestamp = emptyIfNull(rs.getString(3));
                return new ImportProgress(
                    source,
                    rs.getLong(1),
                    rs.getLong(2),
                    lastTimestamp.isBlank() ? null : LocalDateTime.parse(lastTimestamp),
                    rs.getLong(4),
                    ImportStatus.fromStorage(rs.getString(5)),
                    rs.getBoolean(6)
                );
            },
            source
        ));
    }

    void saveImportProgress(ImportProgress progress) {
        String lastTimestamp = progress.lastTimestamp() == null ? "" : progress.lastTimestamp().toString();
        String status = progress.status().storageName();
        upsert(
            "UPDATE import_progress SET processed = ?, total = ?, last_timestamp = ?, skip_count = ?, status = ?, silenced = ? WHERE source_id = ?",
            List.of(progress.processed(), progress.total(), lastTimestamp, progress.skip(), status, progress.silenced(), progress.source()),
            "INSERT INTO import_progress (source_id, processed, total, last_timestamp, skip_count, status, silenced) VALUES (?,?,?,?,?,?,?)",
            List.of(progress.source(), progress.processed(), progress.total(), lastTimestamp, progress.skip(), status, progress.silenced())
        );
    }

    private void indexName(UUID uuid, String username) {
        String lower = lower(username);
        String id = id(uuid);
        if (exists("SELECT player_uuid FROM name_index WHERE username_lower = ? AND player_uuid = ?", lower, id)) {
            return;
        }
        execute("INSERT INTO name_index (username_lower, player_uuid) VALUES (?,?)", lower, id);
    }

    private List<SeenName> listHistory(UUID uuid) {
        return queryAll(
            "SELECT username, last_seen FROM username_history WHERE player_uuid = ? ORDER BY last_seen DESC",
            rs -> new SeenName(rs.getString(1), Instant.ofEpochMilli(rs.getLong(2))),
            id(uuid)
        );
    }

    private int countPlayDays(UUID uuid) {
        Integer count = queryOne("SELECT COUNT(*) FROM play_days WHERE player_uuid = ?", rs -> rs.getInt(1), id(uuid));
        return count == null ? 0 : count;
    }

    private Optional<LocalDate> lastPlayedBefore(UUID uuid, LocalDate excluded) {
        return Optional.ofNullable(queryOne(
            "SELECT MAX(play_day) FROM play_days WHERE player_uuid = ? AND play_day <> ?",
            rs -> {
                String latest = rs.getString(1);
                return latest == null || latest.isBlank() ? null : LocalDate.parse(latest);
            },
            id(uuid),
            excluded.toString()
        ));
    }

    private List<ServerPlay> listServers(UUID uuid) {
        return queryAll(
            "SELECT server_id, minutes FROM play_servers WHERE player_uuid = ? ORDER BY minutes DESC, server_id ASC",
            rs -> new ServerPlay(rs.getString(1), rs.getLong(2)),
            id(uuid)
        );
    }

    private void addServerMinute(UUID uuid, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        if (execute("UPDATE play_servers SET minutes = minutes + 1 WHERE player_uuid = ? AND server_id = ?", id(uuid), serverId) == 0) {
            execute("INSERT INTO play_servers (player_uuid, server_id, minutes) VALUES (?,?,?)", id(uuid), serverId, 1L);
        }
    }

    private void upsert(String updateSql, List<Object> updateParams, String insertSql, List<Object> insertParams) {
        if (execute(updateSql, updateParams.toArray()) == 0) {
            execute(insertSql, insertParams.toArray());
        }
    }

    private boolean exists(String sql, Object... params) {
        return queryOne(sql, rs -> true, params) != null;
    }

    private int execute(String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    private <T> T queryOne(String sql, SqlMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? mapper.map(rows) : null;
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    private <T> List<T> queryAll(String sql, SqlMapper<T> mapper, Object... params) {
        List<T> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(mapper.map(rows));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return values;
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            switch (value) {
                case null -> statement.setString(index, "");
                case String text -> statement.setString(index, text);
                case Long number -> statement.setLong(index, number);
                case Integer number -> statement.setInt(index, number);
                case Boolean flag -> statement.setBoolean(index, flag);
                default -> throw new IllegalArgumentException("unsupported SQL parameter: " + value.getClass().getName());
            }
        }
    }

    private static String id(UUID uuid) {
        return uuid.toString();
    }

    private static String lower(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static RuntimeException wrap(SQLException e) {
        return new IllegalStateException(e);
    }

    private record PlayerFields(String currentUsername, String note, long noteTakenAt, long totalMinutes, int sessionCount) {
    }

    @FunctionalInterface
    private interface SqlMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }
}
