package com.fusion.dev.cystol.fx;

import com.fusion.dev.cystol.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EffectService {

    public enum EffectKey {
        COMPASS_OPEN_GUI("effects.compass.open-gui"),
        MENU_SELECT_TARGET("effects.menu.select-target"),
        MENU_PAGE("effects.menu.page-turn"),
        MENU_DENY("effects.menu.deny"),
        KILL_CREDITED("effects.kill.credited"),
        KILL_GLOBAL("effects.kill.global"),
        HUNT_START("effects.hunt.start"),
        FFA_ANNOUNCE("effects.ffa.announce"),
        FFA_FINAL_COUNTDOWN("effects.ffa.final-countdown"),
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
    private volatile Map<EffectKey, EffectPlan> plans;

    public EffectService(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        rebuildCache();
    }

    /** Call after config reload so paths re-resolve once. */
    public void rebuildCache() {
        EnumMap<EffectKey, EffectPlan> next = new EnumMap<>(EffectKey.class);
        boolean master = config.effectsEnabled();
        for (EffectKey key : EffectKey.values()) {
            next.put(key, EffectResolver.resolve(master, config.effectSection(key.path())));
        }
        this.plans = Map.copyOf(next);
    }

    /**
     * Resolve plan without playing — used by tests and diagnostics.
     */
    public EffectPlan plan(EffectKey key) {
        if (key == null) {
            return EffectPlan.disabled();
        }
        Map<EffectKey, EffectPlan> cache = plans;
        if (cache != null) {
            EffectPlan cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        return EffectResolver.resolve(config.effectsEnabled(), config.effectSection(key.path()));
    }

    public void play(Player player, EffectKey key) {
        if (player == null) {
            return;
        }
        play(player, key, player.getLocation(), null);
    }

    public void play(Player player, EffectKey key, Location at) {
        play(player, key, at, null);
    }

    /**
     * @param pitchOverride if non-null, replaces the plan's configured pitch (e.g. rising countdown)
     */
    public void play(Player player, EffectKey key, Location at, Float pitchOverride) {
        if (player == null || key == null) {
            return;
        }
        EffectPlan plan = plan(key);
        if (!plan.shouldPlay()) {
            return;
        }
        if (plan.hasSound()) {
            playSound(player, plan, pitchOverride);
        }
        if (plan.hasParticle()) {
            playParticle(at != null ? at : player.getLocation(), plan);
        }
    }

    /**
     * Show a title to a player using this effect's configured title timings.
     * The title/subtitle text (with placeholders already applied) is supplied by the caller
     * so it can live in lang.yml for translation.
     *
     * @param title    main title line (already placeholder-resolved)
     * @param subtitle subtitle line (already placeholder-resolved)
     */
    public void showTitle(Player player, EffectKey key, Component title, Component subtitle) {
        if (player == null || key == null) {
            return;
        }
        EffectPlan plan = plan(key);
        if (!plan.hasTitle()) {
            return;
        }
        Title.Times times = Title.Times.times(
                Duration.ofMillis(plan.titleFadeInMs()),
                Duration.ofMillis(plan.titleStayMs()),
                Duration.ofMillis(plan.titleFadeOutMs())
        );
        player.showTitle(Title.title(title, subtitle, times));
    }

    private void playSound(Player player, EffectPlan plan, Float pitchOverride) {
        String name = plan.soundName();
        float volume = plan.volume();
        float pitch = pitchOverride != null ? pitchOverride : plan.pitch();
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

    /**
     * Play an effect to every online player except {@code excluded}
     * (e.g. a global kill sound heard by everyone but the killer).
     */
    public void playToAllExcept(Player excluded, EffectKey key) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (excluded != null && p.getUniqueId().equals(excluded.getUniqueId())) {
                continue;
            }
            play(p, key);
        }
    }
}
