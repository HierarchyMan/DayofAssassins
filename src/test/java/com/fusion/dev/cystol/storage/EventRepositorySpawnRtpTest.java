package com.fusion.dev.cystol.storage;

import com.fusion.dev.cystol.event.EventPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spawn_rtp_done column migrate + load/save semantics matching {@link EventRepository}.
 */
class EventRepositorySpawnRtpTest {

    @TempDir
    Path tempDir;

    @Test
    void openAndMigrateIncludesSpawnRtpDoneDefaultZero() throws Exception {
        File db = tempDir.resolve("spawn-rtp.db").toFile();
        try (Connection c = SqliteAccess.openAndMigrate(db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     """
                             SELECT spawn_rtp_done, ffa_teleported, ceremony_done, paused,
                                    scores_archived,
                                    countdown_anchor_epoch, hunt_entered_epoch, ffa_entered_epoch
                             FROM event_state WHERE id = 1
                             """)) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("spawn_rtp_done"));
            assertEquals(0, rs.getInt("ffa_teleported"));
            assertEquals(0, rs.getInt("ceremony_done"));
            assertEquals(0, rs.getInt("paused"));
            assertEquals(0, rs.getInt("scores_archived"));
            rs.getObject("countdown_anchor_epoch");
            assertTrue(rs.wasNull());
            rs.getObject("hunt_entered_epoch");
            assertTrue(rs.wasNull());
            rs.getObject("ffa_entered_epoch");
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void migrateAddsSpawnRtpDoneToLegacySchema() throws Exception {
        File db = tempDir.resolve("legacy.db").toFile();
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE event_state (
                      id INTEGER PRIMARY KEY CHECK (id = 1),
                      start_epoch INTEGER,
                      end_epoch INTEGER,
                      ffa_override_epoch INTEGER,
                      phase TEXT,
                      ffa_teleported INTEGER NOT NULL DEFAULT 0,
                      ceremony_done INTEGER NOT NULL DEFAULT 0,
                      paused INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            st.executeUpdate(
                    "INSERT INTO event_state (id, phase, ffa_teleported, ceremony_done, paused) VALUES (1, 'HUNT', 0, 0, 0)");
        }

        try (Connection c = SqliteAccess.openAndMigrate(db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT spawn_rtp_done, phase, countdown_anchor_epoch, hunt_entered_epoch, ffa_entered_epoch FROM event_state WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("spawn_rtp_done"));
            assertEquals("HUNT", rs.getString("phase"));
            rs.getObject("countdown_anchor_epoch");
            assertTrue(rs.wasNull());
            rs.getObject("hunt_entered_epoch");
            assertTrue(rs.wasNull());
        }
    }

    /**
     * Exercises the same SELECT/UPDATE shape as {@link EventRepository} for spawn_rtp_done.
     */
    @Test
    void spawnRtpDoneRoundTripViaRepositorySqlShape() throws Exception {
        File db = tempDir.resolve("repo-shape.db").toFile();
        try (Connection c = SqliteAccess.openAndMigrate(db)) {
            Instant start = Instant.parse("2026-07-18T12:00:00Z");
            Instant end = Instant.parse("2026-07-18T18:00:00Z");

            Instant anchor = Instant.parse("2026-07-18T10:00:00Z");
            Instant huntEnter = start;
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE event_state SET
                      start_epoch = ?,
                      end_epoch = ?,
                      ffa_override_epoch = ?,
                      phase = ?,
                      ffa_teleported = ?,
                      ceremony_done = ?,
                      paused = ?,
                      spawn_rtp_done = ?,
                      countdown_anchor_epoch = ?,
                      hunt_entered_epoch = ?,
                      ffa_entered_epoch = ?
                    WHERE id = 1
                    """)) {
                ps.setLong(1, start.getEpochSecond());
                ps.setLong(2, end.getEpochSecond());
                ps.setObject(3, null);
                ps.setString(4, EventPhase.HUNT.name());
                ps.setInt(5, 0);
                ps.setInt(6, 0);
                ps.setInt(7, 0);
                ps.setInt(8, 1);
                ps.setLong(9, anchor.getEpochSecond());
                ps.setLong(10, huntEnter.getEpochSecond());
                ps.setObject(11, null);
                assertEquals(1, ps.executeUpdate());
            }

            try (PreparedStatement ps = c.prepareStatement(
                    """
                            SELECT start_epoch, end_epoch, phase, ffa_teleported, ceremony_done, paused, spawn_rtp_done,
                                   countdown_anchor_epoch, hunt_entered_epoch, ffa_entered_epoch
                            FROM event_state WHERE id = 1
                            """);
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(start.getEpochSecond(), rs.getLong("start_epoch"));
                assertEquals(end.getEpochSecond(), rs.getLong("end_epoch"));
                assertEquals(EventPhase.HUNT.name(), rs.getString("phase"));
                assertEquals(0, rs.getInt("ffa_teleported"));
                assertEquals(0, rs.getInt("ceremony_done"));
                assertEquals(0, rs.getInt("paused"));
                assertEquals(1, rs.getInt("spawn_rtp_done"));
                assertEquals(anchor.getEpochSecond(), rs.getLong("countdown_anchor_epoch"));
                assertEquals(huntEnter.getEpochSecond(), rs.getLong("hunt_entered_epoch"));
                rs.getObject("ffa_entered_epoch");
                assertTrue(rs.wasNull());
            }

            // Clear spawn_rtp_done, set ffa_teleported — independent flags
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE event_state SET ffa_teleported = 1, spawn_rtp_done = 0 WHERE id = 1
                    """)) {
                assertEquals(1, ps.executeUpdate());
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT ffa_teleported, spawn_rtp_done FROM event_state WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("ffa_teleported"));
                assertEquals(0, rs.getInt("spawn_rtp_done"));
            }
        }

        // Re-open: flag persists across migrate (column already present)
        try (Connection c2 = SqliteAccess.openAndMigrate(db);
             Statement st = c2.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT spawn_rtp_done, ffa_teleported, phase FROM event_state WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("spawn_rtp_done"));
            assertEquals(1, rs.getInt("ffa_teleported"));
            assertEquals("HUNT", rs.getString("phase"));
        }
    }

    @Test
    void storedEventRecordCarriesSpawnRtpDone() {
        Instant a = Instant.parse("2026-01-01T00:00:00Z");
        Instant h = Instant.parse("2026-01-01T01:00:00Z");
        EventRepository.StoredEvent done = new EventRepository.StoredEvent(
                null, null, null, EventPhase.HUNT, false, false, false, true, false, a, h, null);
        assertTrue(done.spawnRtpDone());
        assertFalse(done.ffaTeleported());
        assertFalse(done.scoresArchived());
        assertEquals(a, done.countdownAnchor());
        assertEquals(h, done.huntEntered());

        EventRepository.StoredEvent clear = new EventRepository.StoredEvent(
                null, null, null, EventPhase.IDLE, true, true, true, false, true, null, null, null);
        assertFalse(clear.spawnRtpDone());
        assertTrue(clear.ffaTeleported());
        assertTrue(clear.ceremonyDone());
        assertTrue(clear.paused());
        assertTrue(clear.scoresArchived());
    }
}
