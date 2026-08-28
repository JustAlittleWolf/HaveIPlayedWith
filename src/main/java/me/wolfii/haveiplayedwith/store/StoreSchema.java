package me.wolfii.haveiplayedwith.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Table definitions, created once when the database is opened. String columns store
 * empty strings instead of SQL NULL, which is what {@link StoreDb} reads back.
 */
final class StoreSchema {
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

    private StoreSchema() {
    }

    /** Adds any table the database does not have yet, leaving existing ones untouched. */
    static void create(Connection connection) throws SQLException {
        for (String sql : TABLES) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                if (e.getMessage() == null || !e.getMessage().contains("already exists")) {
                    throw e;
                }
            }
        }
    }
}
