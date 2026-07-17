package com.fusion.dev.cystol.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
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
        connection = SqliteAccess.openAndMigrate(db);
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
