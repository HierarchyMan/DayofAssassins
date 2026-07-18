package com.fusion.dev.cystol.compass;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class CompassKeys {

    public final NamespacedKey compassItem;
    public final NamespacedKey wandItem;
    public final NamespacedKey spawnWandItem;
    public final NamespacedKey trackTarget;

    public CompassKeys(JavaPlugin plugin) {
        this.compassItem = new NamespacedKey(plugin, "assassin_compass");
        this.wandItem = new NamespacedKey(plugin, "arena_wand");
        this.spawnWandItem = new NamespacedKey(plugin, "spawn_wand");
        this.trackTarget = new NamespacedKey(plugin, "track_target");
    }
}
