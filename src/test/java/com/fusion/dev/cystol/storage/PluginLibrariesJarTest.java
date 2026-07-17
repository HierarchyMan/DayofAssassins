package com.fusion.dev.cystol.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures the shipped jar stays lean: sqlite is declared for Paper's library loader,
 * not embedded with multi-platform natives.
 */
class PluginLibrariesJarTest {

    @Test
    void pluginYmlDeclaresSqliteLibrary() throws Exception {
        String yml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            Assumptions.assumeTrue(in != null, "plugin.yml on classpath");
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yml.contains("libraries:"), yml);
        assertTrue(yml.contains("org.xerial:sqlite-jdbc:"), yml);
    }

    @Test
    void shadedJarDoesNotEmbedSqliteNatives() throws Exception {
        Path jar = findShadedJar();
        Assumptions.assumeTrue(jar != null && Files.isRegularFile(jar),
                "shaded jar not built yet — run package first");

        try (JarFile jf = new JarFile(jar.toFile())) {
            boolean hasNative = jf.stream().anyMatch(e ->
                    e.getName().startsWith("org/sqlite/native/")
                            || e.getName().endsWith("sqlitejdbc.dll")
                            || e.getName().endsWith("libsqlitejdbc.so")
                            || e.getName().endsWith("libsqlitejdbc.dylib"));
            assertFalse(hasNative, "sqlite natives must not be shaded; use Paper libraries");

            boolean hasJdbc = jf.stream().anyMatch(e -> e.getName().equals("org/sqlite/JDBC.class"));
            assertFalse(hasJdbc, "org.sqlite.JDBC should come from Paper-downloaded library, not our jar");
        }

        long size = Files.size(jar);
        // Triumph-only shade should be well under a couple MB; keep a soft ceiling
        assertTrue(size < 3_000_000L,
                "jar too large (" + size + " bytes); sqlite may still be shaded");
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
