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
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_state (
                      id INTEGER PRIMARY KEY CHECK (id = 1),
                      start_epoch INTEGER,
                      end_epoch INTEGER,
                      ffa_override_epoch INTEGER,
                      phase TEXT,
                      ffa_teleported INTEGER NOT NULL DEFAULT 0,
                      ceremony_done INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS kills (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      kills INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS metrics (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    INSERT OR IGNORE INTO event_state (id, phase, ffa_teleported, ceremony_done)
                    VALUES (1, 'IDLE', 0, 0)
                    """);
        }
        return connection;
    }
}
