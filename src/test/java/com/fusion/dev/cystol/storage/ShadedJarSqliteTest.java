package com.fusion.dev.cystol.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.Statement;
import java.util.Properties;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-package check: shaded plugin jar still exposes unrelocated org.sqlite.JDBC
 * and can open a temp SQLite DB (native + service loader path).
 */
class ShadedJarSqliteTest {

    @TempDir
    Path tempDir;

    @Test
    void shadedJarCanLoadOrgSqliteAndOpenTempDb() throws Exception {
        Path jar = findShadedJar();
        Assumptions.assumeTrue(jar != null && Files.isRegularFile(jar),
                "shaded jar not built yet — run package first");

        try (JarFile jf = new JarFile(jar.toFile())) {
            var entry = jf.getEntry("META-INF/services/java.sql.Driver");
            assertNotNull(entry, "META-INF/services/java.sql.Driver missing from shaded jar");
            String services = new String(jf.getInputStream(entry).readAllBytes());
            assertTrue(services.contains("org.sqlite.JDBC"),
                    "Driver SPI must list org.sqlite.JDBC, got: " + services);
            assertFalse(services.contains("com.fusion.dev.cystol.libs.sqlite"),
                    "org.sqlite must not be relocated in SPI: " + services);
        }

        URL[] urls = {jar.toUri().toURL()};
        try (URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> driverClass = Class.forName("org.sqlite.JDBC", true, cl);
            assertTrue(driverClass.getName().equals("org.sqlite.JDBC"));
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            File db = tempDir.resolve("shaded-smoke.db").toFile();
            String url = "jdbc:sqlite:" + db.getAbsolutePath();
            try (Connection c = driver.connect(url, new Properties());
                 Statement st = c.createStatement()) {
                assertNotNull(c);
                st.execute("CREATE TABLE t(id INTEGER PRIMARY KEY)");
                st.execute("INSERT INTO t(id) VALUES (1)");
                try (var rs = st.executeQuery("SELECT id FROM t")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) == 1);
                }
            }
        }
    }

    private static Path findShadedJar() {
        Path target = Path.of("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (var stream = Files.list(target)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("DayOfAssassins-") && n.endsWith(".jar")
                                && !n.contains("original") && !n.contains("sources");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
