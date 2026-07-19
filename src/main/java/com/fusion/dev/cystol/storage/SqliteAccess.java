package com.fusion.dev.cystol.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Pure SQLite open + schema bootstrap (no Bukkit).
 * Uses unrelocated {@code org.sqlite.JDBC} via {@link DriverManager}.
 */
public final class SqliteAccess {

    private SqliteAccess() {
    }

    /**
     * Ensure JDBC driver is registered, open DB at path, apply Day of Assassins schema.
     */
    public static Connection openAndMigrate(File dbFile) throws SQLException {
        if (dbFile == null) {
            throw new IllegalArgumentException("dbFile");
        }
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create directory: " + parent);
        }
        // Explicit load so ServiceLoader / classloader edge cases still work when shaded
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("org.sqlite.JDBC not on classpath (do not relocate sqlite-jdbc)", e);
        }
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Connection connection = DriverManager.getConnection(url);
        try (Statement st = connection.createStatement()) {
            // Latency-friendly defaults for event writes (async kill flush + rare phase saves).
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_state (
                      id INTEGER PRIMARY KEY CHECK (id = 1),
                      start_epoch INTEGER,
                      end_epoch INTEGER,
                      ffa_override_epoch INTEGER,
                      phase TEXT,
                      ffa_teleported INTEGER NOT NULL DEFAULT 0,
                      ceremony_done INTEGER NOT NULL DEFAULT 0,
                      paused INTEGER NOT NULL DEFAULT 0,
                      spawn_rtp_done INTEGER NOT NULL DEFAULT 0,
                      countdown_anchor_epoch INTEGER,
                      hunt_entered_epoch INTEGER,
                      ffa_entered_epoch INTEGER
                    )
                    """);
            // Upgrade older DBs that lack paused
            try {
                st.executeUpdate("ALTER TABLE event_state ADD COLUMN paused INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            try {
                st.executeUpdate("ALTER TABLE event_state ADD COLUMN spawn_rtp_done INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            // Bossbar segment anchors (wall clock when segment armed / entered)
            try {
                st.executeUpdate("ALTER TABLE event_state ADD COLUMN countdown_anchor_epoch INTEGER");
            } catch (SQLException ignored) {
                // column already exists
            }
            try {
                st.executeUpdate("ALTER TABLE event_state ADD COLUMN hunt_entered_epoch INTEGER");
            } catch (SQLException ignored) {
                // column already exists
            }
            try {
                st.executeUpdate("ALTER TABLE event_state ADD COLUMN ffa_entered_epoch INTEGER");
            } catch (SQLException ignored) {
                // column already exists
            }
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS kills (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      kills INTEGER NOT NULL DEFAULT 0,
                      reached_at_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            // Upgrade older DBs that lack first-to-reach timestamp
            try {
                st.executeUpdate("ALTER TABLE kills ADD COLUMN reached_at_ms INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            // Chronological past games: id 1, 2, 3… (AUTOINCREMENT)
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS past_games (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      ended_at_epoch INTEGER NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS past_game_kills (
                      game_id INTEGER NOT NULL,
                      uuid TEXT NOT NULL,
                      name TEXT NOT NULL,
                      kills INTEGER NOT NULL DEFAULT 0,
                      place INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (game_id, uuid),
                      FOREIGN KEY (game_id) REFERENCES past_games(id) ON DELETE CASCADE
                    )
                    """);
            // One-shot: live scores already copied into past_games for this schedule arm
            try {
                st.executeUpdate(
                        "ALTER TABLE event_state ADD COLUMN scores_archived INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS metrics (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    INSERT OR IGNORE INTO event_state (id, phase, ffa_teleported, ceremony_done, paused)
                    VALUES (1, 'IDLE', 0, 0, 0)
                    """);
        }
        return connection;
    }
}
