package com.fusion.dev.cystol.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Single SQLite connection shared by repositories. All use must go through
 * {@link #withConnection} so main-thread phase saves and async kill flushes never
 * race on the same JDBC connection.
 */
public class SqliteDatabase implements AutoCloseable {

    private final JavaPlugin plugin;
    private final String fileName;
    private final Object lock = new Object();
    private Connection connection;

    public SqliteDatabase(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName == null || fileName.isBlank() ? "data.db" : fileName;
    }

    public void open() throws SQLException {
        synchronized (lock) {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create data folder");
            }
            File db = new File(plugin.getDataFolder(), fileName);
            if (connection != null && !connection.isClosed()) {
                return;
            }
            connection = SqliteAccess.openAndMigrate(db);
            plugin.getLogger().info("SQLite opened: " + db.getName());
        }
    }

    /**
     * @deprecated Prefer {@link #withConnection}; retained for tests that hold a short-lived connection.
     */
    public Connection connection() throws SQLException {
        synchronized (lock) {
            if (connection == null || connection.isClosed()) {
                open();
            }
            return connection;
        }
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    public <T> T withConnection(SqlFunction<T> action) throws SQLException {
        synchronized (lock) {
            if (connection == null || connection.isClosed()) {
                open();
            }
            return action.apply(connection);
        }
    }

    public void withConnection(SqlConsumer action) throws SQLException {
        synchronized (lock) {
            if (connection == null || connection.isClosed()) {
                open();
            }
            action.accept(connection);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
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
}
