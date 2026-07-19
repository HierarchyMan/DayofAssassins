package com.fusion.dev.cystol.storage;

import com.fusion.dev.cystol.kill.DenseRanking;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chronological past game ids 1,2,3… and dense ranking snapshot. */
class PastGameRepositoryTest {

    @TempDir
    Path tempDir;

    private FileBackedDb db;
    private PastGameRepository past;

    @BeforeEach
    void setUp() throws Exception {
        File file = tempDir.resolve("past.db").toFile();
        db = new FileBackedDb(file);
        past = new PastGameRepository(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void gameIdsAreChronologicalAndRankingLoads() throws Exception {
        UUID a = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID b = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        int g1 = past.insertGame(1_700_000_000L, List.of(
                new DenseRanking.Entry(a, "Alice", 5, 1),
                new DenseRanking.Entry(b, "Bob", 3, 2)
        ));
        int g2 = past.insertGame(1_700_000_100L, List.of(
                new DenseRanking.Entry(b, "Bob", 9, 1)
        ));
        assertEquals(1, g1);
        assertEquals(2, g2);
        assertEquals(2, past.gameCount());
        assertEquals(2, past.latestGameId().orElseThrow());

        var latest = past.loadLatest().orElseThrow();
        assertEquals(2, latest.id());
        assertEquals(1, latest.ranking().size());
        assertEquals("Bob", latest.ranking().getFirst().name());
        assertEquals(9, latest.ranking().getFirst().kills());

        var first = past.loadGame(1).orElseThrow();
        assertEquals(2, first.ranking().size());
        assertEquals("Alice", first.ranking().getFirst().name());
        assertEquals(1, first.ranking().getFirst().place());
        assertTrue(past.loadGame(99).isEmpty());
    }

    private static final class FileBackedDb extends SqliteDatabase {
        private final File file;
        private Connection conn;

        @SuppressWarnings("DataFlowIssue")
        FileBackedDb(File file) throws SQLException {
            super((JavaPlugin) null, file.getName());
            this.file = file;
            this.conn = SqliteAccess.openAndMigrate(file);
        }

        @Override
        public void open() {
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
