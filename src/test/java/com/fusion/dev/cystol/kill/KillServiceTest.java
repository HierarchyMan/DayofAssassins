package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.storage.KillRepository;
import com.fusion.dev.cystol.storage.SqliteAccess;
import com.fusion.dev.cystol.storage.SqliteDatabase;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kill memory is source of truth; disk flush is async. Ranking/top cache must stay correct.
 */
class KillServiceTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private Connection verifyConn;
    private FileBackedDb fileDb;
    private KillService service;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("kills.db").toFile();
        fileDb = new FileBackedDb(dbFile);
        verifyConn = SqliteAccess.openAndMigrate(dbFile);
        service = new KillService(new KillRepository(fileDb), Logger.getLogger("KillServiceTest"));
        service.load();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.shutdown();
            service = null;
        }
        if (fileDb != null) {
            fileDb.close();
        }
        if (verifyConn != null) {
            verifyConn.close();
        }
    }

    @Test
    void topKillerTracksHighestKills() {
        UUID a = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID b = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        service.creditKill(a, "Alice");
        service.creditKill(b, "Bob");
        service.creditKill(b, "Bob");
        assertEquals("Bob", service.topKiller().orElseThrow().name());
        assertEquals(2, service.topKiller().orElseThrow().kills());
        assertEquals(1, service.topKiller().orElseThrow().place());
        assertEquals(2, service.ranking().size());
        // Live = competition places; Alice is #2 not shared #1
        assertEquals(2, service.ranking().get(1).place());
        assertEquals(1, service.rankingFinal().get(0).place());
    }

    @Test
    void firstToReachKeepsLiveLeadOnEqualKills() throws Exception {
        UUID early = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID late = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        service.creditKill(early, "Alice");
        service.creditKill(early, "Alice");
        Thread.sleep(5L);
        service.creditKill(late, "Zed");
        service.creditKill(late, "Zed");
        assertEquals("Alice", service.ranking().get(0).name());
        assertEquals(1, service.ranking().get(0).place());
        assertEquals("Zed", service.ranking().get(1).name());
        assertEquals(2, service.ranking().get(1).place());
        // Final dense: both place 1
        assertEquals(1, service.rankingFinal().get(0).place());
        assertEquals(1, service.rankingFinal().get(1).place());
    }

    @Test
    void shutdownFlushesToDisk() throws Exception {
        UUID a = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        service.creditKill(a, "Cara");
        service.creditKill(a, "Cara");
        service.shutdown();
        service = null;

        try (Statement st = verifyConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT kills, name FROM kills WHERE name = 'Cara'")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("kills"));
        }
    }

    @Test
    void clearAllWipesMemoryRankingAndDisk() throws Exception {
        UUID a = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        service.creditKill(a, "Dana");
        service.creditKill(a, "Dana");
        assertEquals(1, service.ranking().size());
        service.clearAll();
        assertEquals(0, service.ranking().size());
        assertTrue(service.topKiller().isEmpty());
        // Drain async clear
        service.shutdown();
        service = null;
        try (Statement st = verifyConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM kills")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("c"));
        }
    }

    /** Minimal SqliteDatabase stand-in that does not need a live Bukkit plugin. */
    private static final class FileBackedDb extends SqliteDatabase {
        private final File file;
        private Connection conn;

        @SuppressWarnings("DataFlowIssue")
        FileBackedDb(File file) throws SQLException {
            // plugin is unused: open/withConnection are overridden
            super((JavaPlugin) null, file.getName());
            this.file = file;
            this.conn = SqliteAccess.openAndMigrate(file);
        }

        @Override
        public void open() {
            // no-op
        }

        @Override
        public <T> T withConnection(SqlFunction<T> action) throws SQLException {
            synchronized (this) {
                ensureOpen();
                return action.apply(conn);
            }
        }

        @Override
        public void withConnection(SqlConsumer action) throws SQLException {
            synchronized (this) {
                ensureOpen();
                action.accept(conn);
            }
        }

        private void ensureOpen() throws SQLException {
            if (conn == null || conn.isClosed()) {
                conn = SqliteAccess.openAndMigrate(file);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException ignored) {
                    }
                    conn = null;
                }
            }
        }
    }
}
