package com.fusion.dev.cystol.fx;

import com.fusion.dev.cystol.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EffectService {

    public enum EffectKey {
        COMPASS_OPEN_GUI("effects.compass.open-gui"),
        MENU_SELECT_TARGET("effects.menu.select-target"),
        MENU_PAGE("effects.menu.page-turn"),
        MENU_DENY("effects.menu.deny"),
        KILL_CREDITED("effects.kill.credited"),
        FFA_ANNOUNCE("effects.ffa.announce"),
        FFA_TELEPORT("effects.ffa.teleport"),
        END_NORMAL("effects.end.normal"),
        END_TOP3("effects.end.top3");

        private final String path;

        EffectKey(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    private final PluginConfig config;
    private final Logger logger;

    public EffectService(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void play(Player player, EffectKey key) {
        play(player, key, player.getLocation());
    }

    public void play(Player player, EffectKey key, Location at) {
        if (player == null || key == null || !config.effectsEnabled()) {
            return;
        }
        ConfigurationSection section = config.effectSection(key.path());
        if (section == null) {
            return;
        }
        playSound(player, section.getConfigurationSection("sound"));
        playParticle(player, at != null ? at : player.getLocation(), section.getConfigurationSection("particle"));
    }

    private void playSound(Player player, ConfigurationSection sound) {
        if (sound == null || !sound.getBoolean("enabled", true)) {
            return;
        }
        String name = sound.getString("sound", "");
        float volume = (float) sound.getDouble("volume", 1.0);
        float pitch = (float) sound.getDouble("pitch", 1.0);
        try {
            if (name.startsWith("minecraft:")) {
                player.playSound(player.getLocation(), name, volume, pitch);
            } else {
                Sound s = Sound.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
                player.playSound(player.getLocation(), s, volume, pitch);
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Invalid sound " + name, e);
            try {
                player.playSound(player.getLocation(), name, volume, pitch);
            } catch (Exception ignored) {
            }
        }
    }

    private void playParticle(Player player, Location at, ConfigurationSection particle) {
        if (particle == null || !particle.getBoolean("enabled", true) || at == null || at.getWorld() == null) {
            return;
        }
        String name = particle.getString("particle", "CRIT");
        int count = particle.getInt("count", 8);
        double ox = particle.getDouble("offset-x", 0.25);
        double oy = particle.getDouble("offset-y", 0.3);
        double oz = particle.getDouble("offset-z", 0.25);
        try {
            Particle p = Particle.valueOf(name.toUpperCase(Locale.ROOT));
            at.getWorld().spawnParticle(p, at.clone().add(0, 1, 0), count, ox, oy, oz, 0.01);
        } catch (Exception e) {
            logger.log(Level.FINE, "Invalid particle " + name, e);
        }
    }

    public void playToAll(EffectKey key) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            play(p, key);
        }
    }
}
