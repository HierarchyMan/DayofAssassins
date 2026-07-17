package com.fusion.dev.cystol.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public final class SqliteDatabase implements AutoCloseable {

    private final JavaPlugin plugin;
    private final String fileName;
    private Connection connection;

    public SqliteDatabase(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName == null || fileName.isBlank() ? "data.db" : fileName;
    }

    public void open() throws SQLException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create data folder");
        }
        File db = new File(plugin.getDataFolder(), fileName);
        String url = "jdbc:sqlite:" + db.getAbsolutePath();
        connection = DriverManager.getConnection(url);
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
        plugin.getLogger().info("SQLite opened: " + db.getName());
    }

    public Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            open();
        }
        return connection;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing SQLite", e);
            }
            connection = null;
        }
    }
}
