package com.fusion.dev.cystol.config;

import com.fusion.dev.cystol.config.yaml.ManagedYamlFiles;
import com.fusion.dev.cystol.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

public final class Lang {

    private final JavaPlugin plugin;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private FileConfiguration lang;
    private File langFile;

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        lock.writeLock().lock();
        try {
            // Create if missing + insert any new jar keys without wiping comments/values.
            ManagedYamlFiles.update(plugin, "lang.yml");
            langFile = new File(plugin.getDataFolder(), "lang.yml");
            lang = YamlConfiguration.loadConfiguration(langFile);
            InputStream def = plugin.getResource("lang.yml");
            if (def != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(def, StandardCharsets.UTF_8));
                lang.setDefaults(defaults);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void reload() {
        load();
    }

    private FileConfiguration cfg() {
        lock.readLock().lock();
        try {
            if (lang == null) {
                throw new IllegalStateException("Lang not loaded");
            }
            return lang;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String raw(String path) {
        String v = cfg().getString(path);
        if (v == null) {
            plugin.getLogger().log(Level.WARNING, "Missing lang key: {0}", path);
            return path;
        }
        return v;
    }

    public String raw(String path, String def) {
        return cfg().getString(path, def);
    }

    public List<String> rawList(String path) {
        List<String> list = cfg().getStringList(path);
        if (list == null || list.isEmpty()) {
            String single = cfg().getString(path);
            if (single != null) {
                return List.of(single);
            }
            plugin.getLogger().log(Level.WARNING, "Missing lang list key: {0}", path);
            return List.of(path);
        }
        return Collections.unmodifiableList(list);
    }

    public Component msg(String path) {
        return TextUtil.component(raw(path), Map.of());
    }

    public Component msg(String path, Map<String, String> placeholders) {
        return TextUtil.component(raw(path), placeholders);
    }

    public List<Component> msgList(String path, Map<String, String> placeholders) {
        return TextUtil.componentList(rawList(path), placeholders);
    }

    public String string(String path, Map<String, String> placeholders) {
        return TextUtil.apply(raw(path), placeholders);
    }

    public List<String> stringList(String path, Map<String, String> placeholders) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : rawList(path)) {
            out.add(TextUtil.apply(line, placeholders));
        }
        return out;
    }
}
