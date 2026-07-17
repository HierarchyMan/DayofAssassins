package com.fusion.dev.cystol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the shipped {@link SqliteAccess#openAndMigrate(File)} path used by production
 * {@link SqliteDatabase} — real jdbc:sqlite + org.sqlite.JDBC (must not be relocated).
 */
class SqliteAccessTest {

    @TempDir
    Path tempDir;

    @Test
    void openAndMigrateCreatesSchemaAndAcceptsWrites() throws Exception {
        File db = tempDir.resolve("test-data.db").toFile();
        try (Connection c = SqliteAccess.openAndMigrate(db)) {
            assertTrue(c.isValid(2));

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT phase, ffa_teleported, ceremony_done FROM event_state WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("IDLE", rs.getString("phase"));
                assertEquals(0, rs.getInt("ffa_teleported"));
                assertEquals(0, rs.getInt("ceremony_done"));
            }

            UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO kills(uuid, name, kills) VALUES(?, ?, ?)")) {
                ps.setString(1, id.toString());
                ps.setString(2, "Tester");
                ps.setInt(3, 7);
                assertEquals(1, ps.executeUpdate());
            }

            try (PreparedStatement ps = c.prepareStatement("SELECT kills FROM kills WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(7, rs.getInt(1));
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO metrics(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, "smoke");
                ps.setString(2, "ok");
                ps.executeUpdate();
            }
        }

        // Re-open same file — schema persists, no CNFE on driver
        try (Connection c2 = SqliteAccess.openAndMigrate(db);
             Statement st = c2.createStatement();
             ResultSet rs = st.executeQuery("SELECT kills FROM kills WHERE name = 'Tester'")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getInt(1));
        }
    }

    @Test
    void jdbcDriverClassIsOrgSqliteNotRelocated() throws Exception {
        Class<?> driver = Class.forName("org.sqlite.JDBC");
        assertEquals("org.sqlite.JDBC", driver.getName());
        assertTrue(driver.getName().startsWith("org.sqlite."),
                "sqlite-jdbc must not be relocated; got " + driver.getName());
    }
}
