package com.fusion.dev.cystol.config.yaml;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Registry of plugin data-folder YAML documents that track jar defaults.
 *
 * <p>{@code paper-plugin.yml} is intentionally excluded — it is a Paper plugin
 * descriptor inside the jar, not a user-editable data file.
 */
public final class ManagedYamlFiles {

    /** User-facing YAML files in the plugin data folder (order = update order). */
    public static final List<String> ALL = List.of(
            "config.yml",
            "lang.yml"
    );

    private ManagedYamlFiles() {
    }

    /**
     * For every managed YAML: copy from jar if missing, then merge any keys that
     * exist in the jar default but not in the on-disk file (comments preserved).
     */
    public static void updateAll(JavaPlugin plugin) {
        for (String name : ALL) {
            update(plugin, name);
        }
    }

    /**
     * Update a single managed YAML against its jar resource of the same name.
     *
     * @return keys added (0 if already complete or resource missing)
     */
    public static int update(JavaPlugin plugin, String resourceName) {
        File dest = new File(plugin.getDataFolder(), resourceName);
        if (!dest.exists()) {
            plugin.saveResource(resourceName, false);
        }

        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                plugin.getLogger().log(Level.WARNING, "Missing jar resource for YAML defaults: {0}", resourceName);
                return 0;
            }
            CommentPreservingYaml.MergeResult result =
                    CommentPreservingYaml.mergeMissing(dest.toPath(), in);
            if (result.keysAdded() > 0) {
                plugin.getLogger().info(() ->
                        "YAML " + resourceName + ": added " + result.keysAdded()
                                + " missing key(s) (comments preserved)");
            }
            return result.keysAdded();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to merge defaults into " + resourceName + " — using file as-is", e);
            return 0;
        }
    }

    /**
     * Comment-preserving path writes for {@code config.yml} (or another managed file).
     * Does <strong>not</strong> go through Bukkit {@code saveConfig()}, which strips comments.
     */
    public static void patch(JavaPlugin plugin, String resourceName, Map<String, ?> pathValues) {
        if (pathValues == null || pathValues.isEmpty()) {
            return;
        }
        Path file = new File(plugin.getDataFolder(), resourceName).toPath();
        try {
            // Ensure defaults exist before patching (first boot / wiped file)
            update(plugin, resourceName);
            CommentPreservingYaml.patch(file, pathValues);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to patch " + resourceName + " (comments-preserving write)", e);
            throw new IllegalStateException("YAML patch failed: " + resourceName, e);
        }
    }

    /** Convenience: single-path patch on {@code config.yml}. */
    public static void patchConfig(JavaPlugin plugin, String path, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(path, value);
        patch(plugin, "config.yml", map);
    }

    /** Convenience: multi-path patch on {@code config.yml}. */
    public static void patchConfig(JavaPlugin plugin, Map<String, ?> pathValues) {
        patch(plugin, "config.yml", pathValues);
    }
}
