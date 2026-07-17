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

    /**
     * Resolve plan without playing — used by tests and diagnostics.
     */
    public EffectPlan plan(EffectKey key) {
        if (key == null) {
            return EffectPlan.disabled();
        }
        return EffectResolver.resolve(config.effectsEnabled(), config.effectSection(key.path()));
    }

    public void play(Player player, EffectKey key) {
        if (player == null) {
            return;
        }
        play(player, key, player.getLocation());
    }

    public void play(Player player, EffectKey key, Location at) {
        if (player == null || key == null) {
            return;
        }
        EffectPlan plan = plan(key);
        if (!plan.shouldPlay()) {
            return;
        }
        if (plan.hasSound()) {
            playSound(player, plan);
        }
        if (plan.hasParticle()) {
            playParticle(at != null ? at : player.getLocation(), plan);
        }
    }

    private void playSound(Player player, EffectPlan plan) {
        String name = plan.soundName();
        float volume = plan.volume();
        float pitch = plan.pitch();
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

    private void playParticle(Location at, EffectPlan plan) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        String name = plan.particleName();
        try {
            Particle p = Particle.valueOf(name.toUpperCase(Locale.ROOT));
            at.getWorld().spawnParticle(
                    p,
                    at.clone().add(0, 1, 0),
                    plan.particleCount(),
                    plan.offsetX(),
                    plan.offsetY(),
                    plan.offsetZ(),
                    0.01
            );
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
