package com.fusion.dev.cystol.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper-only packaging: paper-plugin.yml present, no Bukkit plugin.yml,
 * sqlite via loader (not shaded natives).
 */
class PluginLibrariesJarTest {

    @Test
    void paperPluginYmlPresentWithLoaderAndDeps() throws Exception {
        String yml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("paper-plugin.yml")) {
            assertNotNull(in, "paper-plugin.yml must be on classpath");
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yml.contains("main: com.fusion.dev.cystol.DayOfAssassinsPlugin"), yml);
        assertTrue(yml.contains("loader: com.fusion.dev.cystol.DayOfAssassinsLoader"), yml);
        assertTrue(yml.contains("TAB:"), yml);
        assertTrue(yml.contains("PvPManager:"), yml);
        assertTrue(yml.contains("join-classpath: true"), yml);
    }

    @Test
    void noBukkitPluginYmlInProjectResources() throws Exception {
        // Classpath may contain other jars' plugin.yml; assert ours is gone from module output
        Path moduleYml = Path.of("src/main/resources/plugin.yml");
        assertFalse(Files.exists(moduleYml), "src/main/resources/plugin.yml must not exist");
        Path built = Path.of("target/classes/plugin.yml");
        assertFalse(Files.exists(built), "target/classes/plugin.yml must not exist after compile");
    }

    @Test
    void shadedJarIsLeanWithoutSqliteNatives() throws Exception {
        Path jar = findShadedJar();
        Assumptions.assumeTrue(jar != null && Files.isRegularFile(jar),
                "shaded jar not built yet — run package first");

        try (JarFile jf = new JarFile(jar.toFile())) {
            assertTrue(jf.getEntry("paper-plugin.yml") != null, "paper-plugin.yml inside jar");
            assertTrue(jf.getEntry("plugin.yml") == null, "must not ship plugin.yml");
            boolean hasNative = jf.stream().anyMatch(e ->
                    e.getName().startsWith("org/sqlite/native/")
                            || e.getName().endsWith("sqlitejdbc.dll")
                            || e.getName().endsWith("libsqlitejdbc.so"));
            assertFalse(hasNative, "sqlite natives must not be shaded");
            assertTrue(jf.getEntry("com/fusion/dev/cystol/DayOfAssassinsLoader.class") != null);
        }

        long size = Files.size(jar);
        assertTrue(size < 3_000_000L, "jar too large (" + size + " bytes)");
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
