package me.wolfii.haveiplayedwith.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SmallSQL tables for players, sightings, and caches. String columns store empty
 * strings instead of SQL NULL.
 */
final class StoreDb {
    private static final String[] TABLES = {
        """
        CREATE TABLE players (
            player_uuid VARCHAR(36) PRIMARY KEY,
            current_username VARCHAR(64) NOT NULL,
            note LONGVARCHAR,
            note_taken_at BIGINT,
            total_minutes BIGINT,
            session_count INT
        )
        """,
        """
        CREATE TABLE username_history (
            player_uuid VARCHAR(36),
            username_lower VARCHAR(64),
            username VARCHAR(64),
            last_seen BIGINT,
            PRIMARY KEY (player_uuid, username_lower)
        )
        """,
        """
        CREATE TABLE name_index (
            username_lower VARCHAR(64),
            player_uuid VARCHAR(36),
            PRIMARY KEY (username_lower, player_uuid)
        )
        """,
        """
        CREATE TABLE play_days (
            player_uuid VARCHAR(36),
            play_day VARCHAR(10),
            minutes BIGINT,
            PRIMARY KEY (player_uuid, play_day)
        )
        """,
        """
        CREATE TABLE play_sessions (
            player_uuid VARCHAR(36),
            session_id LONGVARCHAR,
            minutes BIGINT,
            PRIMARY KEY (player_uuid, session_id)
        )
        """,
        """
        CREATE TABLE play_servers (
            player_uuid VARCHAR(36),
            server_id LONGVARCHAR,
            minutes BIGINT,
            PRIMARY KEY (player_uuid, server_id)
        )
        """,
        """
        CREATE TABLE mojang_uuid (
            player_uuid VARCHAR(36) PRIMARY KEY,
            username VARCHAR(64),
            fetched_at BIGINT
        )
        """,
        """
        CREATE TABLE mojang_name (
            username_lower VARCHAR(64) PRIMARY KEY,
            player_uuid VARCHAR(36),
            username VARCHAR(64),
            fetched_at BIGINT
        )
        """,
        """
        CREATE TABLE import_progress (
            source_id VARCHAR(64) PRIMARY KEY,
            processed BIGINT,
            total BIGINT,
            last_timestamp VARCHAR(64),
            skip_count BIGINT,
            status VARCHAR(32),
            silenced BIT
        )
        """
    };

    private final Connection connection;

    StoreDb(Connection connection) {
        this.connection = connection;
    }

    void createTables() {
        for (String sql : TABLES) {
            createTable(sql);
        }
    }

    StoreRows.PlayerRow player(UUID uuid) {
        return queryOne(
            "SELECT current_username, note, note_taken_at, total_minutes, session_count FROM players WHERE player_uuid = ?",
            rs -> new StoreRows.PlayerRow(
                rs.getString(1),
                emptyIfNull(rs.getString(2)),
                rs.getLong(3),
                rs.getLong(4),
                rs.getInt(5)
            ),
            StoreKeys.uuid(uuid)
        );
    }

    void putPlayer(UUID uuid, StoreRows.PlayerRow row) {
        upsert(
            "SELECT player_uuid FROM players WHERE player_uuid = ?",
            "UPDATE players SET current_username = ?, note = ?, note_taken_at = ?, total_minutes = ?, session_count = ? WHERE player_uuid = ?",
            "INSERT INTO players (player_uuid, current_username, note, note_taken_at, total_minutes, session_count) VALUES (?,?,?,?,?,?)",
            List.of(StoreKeys.uuid(uuid)),
            List.of(row.currentUsername(), row.note(), row.noteTakenAt(), row.totalMinutes(), row.sessionCount(), StoreKeys.uuid(uuid)),
            List.of(StoreKeys.uuid(uuid), row.currentUsername(), row.note(), row.noteTakenAt(), row.totalMinutes(), row.sessionCount())
        );
    }

    StoreRows.HistoryRow history(UUID uuid, String username) {
        return queryOne(
            "SELECT username, last_seen FROM username_history WHERE player_uuid = ? AND username_lower = ?",
            rs -> new StoreRows.HistoryRow(rs.getString(1), rs.getLong(2)),
            StoreKeys.uuid(uuid),
            StoreKeys.nameIndex(username)
        );
    }

    void putHistory(UUID uuid, StoreRows.HistoryRow row) {
        upsert(
            "SELECT player_uuid FROM username_history WHERE player_uuid = ? AND username_lower = ?",
            "UPDATE username_history SET username = ?, last_seen = ? WHERE player_uuid = ? AND username_lower = ?",
            "INSERT INTO username_history (player_uuid, username_lower, username, last_seen) VALUES (?,?,?,?)",
            List.of(StoreKeys.uuid(uuid), StoreKeys.nameIndex(row.username())),
            List.of(row.username(), row.lastSeen(), StoreKeys.uuid(uuid), StoreKeys.nameIndex(row.username())),
            List.of(StoreKeys.uuid(uuid), StoreKeys.nameIndex(row.username()), row.username(), row.lastSeen())
        );
    }

    List<StoreRows.HistoryRow> listHistory(UUID uuid) {
        List<StoreRows.HistoryRow> rows = queryAll(
            "SELECT username, last_seen FROM username_history WHERE player_uuid = ?",
            rs -> new StoreRows.HistoryRow(rs.getString(1), rs.getLong(2)),
            StoreKeys.uuid(uuid)
        );
        rows.sort(Comparator.comparingLong(StoreRows.HistoryRow::lastSeen).reversed());
        return rows;
    }

    List<UUID> findNameIndex(String username) {
        return queryAll(
            "SELECT player_uuid FROM name_index WHERE username_lower = ?",
            rs -> UUID.fromString(rs.getString(1)),
            StoreKeys.nameIndex(username)
        );
    }

    void indexName(UUID uuid, String username) {
        String lower = StoreKeys.nameIndex(username);
        String id = StoreKeys.uuid(uuid);
        if (exists("SELECT player_uuid FROM name_index WHERE username_lower = ? AND player_uuid = ?", lower, id)) {
            return;
        }
        execute("INSERT INTO name_index (username_lower, player_uuid) VALUES (?,?)", lower, id);
    }

    Long playDayMinutes(UUID uuid, LocalDate day) {
        return queryOne(
            "SELECT minutes FROM play_days WHERE player_uuid = ? AND play_day = ?",
            rs -> rs.getLong(1),
            StoreKeys.uuid(uuid),
            day.toString()
        );
    }

    void putPlayDayIfAbsent(UUID uuid, LocalDate day) {
        if (playDayMinutes(uuid, day) != null) {
            return;
        }
        execute("INSERT INTO play_days (player_uuid, play_day, minutes) VALUES (?,?,?)", StoreKeys.uuid(uuid), day.toString(), 0L);
    }

    void putPlayDay(UUID uuid, LocalDate day, long minutes) {
        upsert(
            "SELECT player_uuid FROM play_days WHERE player_uuid = ? AND play_day = ?",
            "UPDATE play_days SET minutes = ? WHERE player_uuid = ? AND play_day = ?",
            "INSERT INTO play_days (player_uuid, play_day, minutes) VALUES (?,?,?)",
            List.of(StoreKeys.uuid(uuid), day.toString()),
            List.of(minutes, StoreKeys.uuid(uuid), day.toString()),
            List.of(StoreKeys.uuid(uuid), day.toString(), minutes)
        );
    }

    int countPlayDays(UUID uuid) {
        Integer count = queryOne(
            "SELECT COUNT(*) FROM play_days WHERE player_uuid = ?",
            rs -> rs.getInt(1),
            StoreKeys.uuid(uuid)
        );
        return count == null ? 0 : count;
    }

    Optional<LocalDate> lastPlayedBefore(UUID uuid, LocalDate excluded) {
        String latest = queryOne(
            "SELECT MAX(play_day) FROM play_days WHERE player_uuid = ? AND play_day <> ?",
            rs -> rs.getString(1),
            StoreKeys.uuid(uuid),
            excluded.toString()
        );
        return latest == null || latest.isBlank() ? Optional.empty() : Optional.of(LocalDate.parse(latest));
    }

    Long sessionMinutes(UUID uuid, String sessionId) {
        return queryOne(
            "SELECT minutes FROM play_sessions WHERE player_uuid = ? AND session_id = ?",
            rs -> rs.getLong(1),
            StoreKeys.uuid(uuid),
            sessionId
        );
    }

    boolean hasSession(UUID uuid, String sessionId) {
        return sessionMinutes(uuid, sessionId) != null;
    }

    void putSession(UUID uuid, String sessionId, long minutes) {
        upsert(
            "SELECT player_uuid FROM play_sessions WHERE player_uuid = ? AND session_id = ?",
            "UPDATE play_sessions SET minutes = ? WHERE player_uuid = ? AND session_id = ?",
            "INSERT INTO play_sessions (player_uuid, session_id, minutes) VALUES (?,?,?)",
            List.of(StoreKeys.uuid(uuid), sessionId),
            List.of(minutes, StoreKeys.uuid(uuid), sessionId),
            List.of(StoreKeys.uuid(uuid), sessionId, minutes)
        );
    }

    Long serverMinutes(UUID uuid, String serverId) {
        return queryOne(
            "SELECT minutes FROM play_servers WHERE player_uuid = ? AND server_id = ?",
            rs -> rs.getLong(1),
            StoreKeys.uuid(uuid),
            serverId
        );
    }

    void putServer(UUID uuid, String serverId, long minutes) {
        upsert(
            "SELECT player_uuid FROM play_servers WHERE player_uuid = ? AND server_id = ?",
            "UPDATE play_servers SET minutes = ? WHERE player_uuid = ? AND server_id = ?",
            "INSERT INTO play_servers (player_uuid, server_id, minutes) VALUES (?,?,?)",
            List.of(StoreKeys.uuid(uuid), serverId),
            List.of(minutes, StoreKeys.uuid(uuid), serverId),
            List.of(StoreKeys.uuid(uuid), serverId, minutes)
        );
    }

    List<ServerPlay> listServers(UUID uuid) {
        List<ServerPlay> servers = queryAll(
            "SELECT server_id, minutes FROM play_servers WHERE player_uuid = ?",
            rs -> new ServerPlay(rs.getString(1), rs.getLong(2)),
            StoreKeys.uuid(uuid)
        );
        servers.sort(Comparator.comparingLong(ServerPlay::minutes).reversed().thenComparing(ServerPlay::serverId));
        return servers;
    }

    StoreRows.MojangUuidRow mojangUuid(UUID uuid) {
        return queryOne(
            "SELECT username, fetched_at FROM mojang_uuid WHERE player_uuid = ?",
            rs -> new StoreRows.MojangUuidRow(emptyIfNull(rs.getString(1)), rs.getLong(2)),
            StoreKeys.uuid(uuid)
        );
    }

    void putMojangUuid(UUID uuid, StoreRows.MojangUuidRow row) {
        upsert(
            "SELECT player_uuid FROM mojang_uuid WHERE player_uuid = ?",
            "UPDATE mojang_uuid SET username = ?, fetched_at = ? WHERE player_uuid = ?",
            "INSERT INTO mojang_uuid (player_uuid, username, fetched_at) VALUES (?,?,?)",
            List.of(StoreKeys.uuid(uuid)),
            List.of(row.username(), row.fetchedAt(), StoreKeys.uuid(uuid)),
            List.of(StoreKeys.uuid(uuid), row.username(), row.fetchedAt())
        );
    }

    StoreRows.MojangNameRow mojangName(String usernameLower) {
        return queryOne(
            "SELECT player_uuid, username, fetched_at FROM mojang_name WHERE username_lower = ?",
            rs -> new StoreRows.MojangNameRow(emptyIfNull(rs.getString(1)), emptyIfNull(rs.getString(2)), rs.getLong(3)),
            usernameLower
        );
    }

    void putMojangName(String usernameLower, StoreRows.MojangNameRow row) {
        upsert(
            "SELECT username_lower FROM mojang_name WHERE username_lower = ?",
            "UPDATE mojang_name SET player_uuid = ?, username = ?, fetched_at = ? WHERE username_lower = ?",
            "INSERT INTO mojang_name (username_lower, player_uuid, username, fetched_at) VALUES (?,?,?,?)",
            List.of(usernameLower),
            List.of(row.uuid(), row.username(), row.fetchedAt(), usernameLower),
            List.of(usernameLower, row.uuid(), row.username(), row.fetchedAt())
        );
    }

    StoreRows.ImportRow importProgress(String source) {
        return queryOne(
            "SELECT processed, total, last_timestamp, skip_count, status, silenced FROM import_progress WHERE source_id = ?",
            rs -> new StoreRows.ImportRow(
                rs.getLong(1),
                rs.getLong(2),
                emptyIfNull(rs.getString(3)),
                rs.getLong(4),
                rs.getString(5),
                rs.getBoolean(6)
            ),
            source
        );
    }

    void putImportProgress(String source, StoreRows.ImportRow row) {
        upsert(
            "SELECT source_id FROM import_progress WHERE source_id = ?",
            "UPDATE import_progress SET processed = ?, total = ?, last_timestamp = ?, skip_count = ?, status = ?, silenced = ? WHERE source_id = ?",
            "INSERT INTO import_progress (source_id, processed, total, last_timestamp, skip_count, status, silenced) VALUES (?,?,?,?,?,?,?)",
            List.of(source),
            List.of(row.processed(), row.total(), row.lastTimestamp(), row.skip(), row.status(), row.silenced(), source),
            List.of(source, row.processed(), row.total(), row.lastTimestamp(), row.skip(), row.status(), row.silenced())
        );
    }

    private void createTable(String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                return;
            }
            throw wrap(e);
        }
    }

    private void upsert(String existsSql, String updateSql, String insertSql, List<Object> existsParams, List<Object> updateParams, List<Object> insertParams) {
        if (exists(existsSql, existsParams.toArray())) {
            execute(updateSql, updateParams.toArray());
        } else {
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
                if (!rows.next()) {
                    return null;
                }
                return mapper.map(rows);
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

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static RuntimeException wrap(SQLException e) {
        return new IllegalStateException(e);
    }

    @FunctionalInterface
    private interface SqlMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }
}
