package com.fusion.dev.cystol.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packaging regressions only: Paper loader layout, no Bukkit plugin.yml,
 * no shaded SQLite natives, no shaded SnakeYAML (Paper provides 2.2).
 */
class PluginLibrariesJarTest {

    @Test
    void noBukkitPluginYmlInModule() {
        assertFalse(Files.exists(Path.of("src/main/resources/plugin.yml")));
        assertFalse(Files.exists(Path.of("target/classes/plugin.yml")));
    }

    @Test
    void shadedJarIsLeanPaperOnlyWithoutSqliteNatives() throws Exception {
        Path jar = findShadedJar();
        Assumptions.assumeTrue(jar != null && Files.isRegularFile(jar),
                "shaded jar not built yet — run package first");

        try (JarFile jf = new JarFile(jar.toFile())) {
            assertTrue(jf.getEntry("paper-plugin.yml") != null);
            assertTrue(jf.getEntry("plugin.yml") == null);
            assertTrue(jf.getEntry("com/fusion/dev/cystol/DayOfAssassinsLoader.class") != null);
            assertTrue(jf.getEntry("com/fusion/dev/cystol/config/yaml/CommentPreservingYaml.class") != null);
            boolean hasNative = jf.stream().anyMatch(e ->
                    e.getName().startsWith("org/sqlite/native/")
                            || e.getName().endsWith("sqlitejdbc.dll")
                            || e.getName().endsWith("libsqlitejdbc.so"));
            assertFalse(hasNative, "sqlite natives must not be shaded");
            // SnakeYAML is provided by Paper (libraries/org/yaml/snakeyaml/2.2).
            // Shading a second copy risks NoSuchMethodError / comment API skew.
            boolean hasSnakeYaml = jf.stream().anyMatch(e ->
                    e.getName().startsWith("org/yaml/snakeyaml/"));
            assertFalse(hasSnakeYaml, "snakeyaml must not be shaded — use Paper's 2.2");
        }
        assertTrue(Files.size(jar) < 3_000_000L, "jar too large");
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
