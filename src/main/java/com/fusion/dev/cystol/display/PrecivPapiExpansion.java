package com.fusion.dev.cystol.display;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PlaceholderAPI expansion: {@code %preciv_*%}.
 * Only constructed when PlaceholderAPI is present (soft depend).
 */
public final class PrecivPapiExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final PrecivPlaceholderExpansion bridge;

    public PrecivPapiExpansion(JavaPlugin plugin, PrecivPlaceholderExpansion bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    @Override
    public String getIdentifier() {
        return "preciv";
    }

    @Override
    public String getAuthor() {
        return "cystol";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return bridge.resolve(params, player != null ? player.getUniqueId() : null);
    }
}
