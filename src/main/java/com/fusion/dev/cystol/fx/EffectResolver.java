package com.fusion.dev.cystol.fx;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure effect config resolution used by {@link EffectService}.
 */
public final class EffectResolver {

    private EffectResolver() {
    }

    public static EffectPlan resolve(boolean masterEnabled, ConfigurationSection effectRoot) {
        if (!masterEnabled) {
            return EffectPlan.disabled();
        }
        if (effectRoot == null) {
            return EffectPlan.disabled();
        }

        ConfigurationSection sound = effectRoot.getConfigurationSection("sound");
        boolean soundEnabled = false;
        String soundName = "";
        float volume = 1f;
        float pitch = 1f;
        if (sound != null) {
            soundEnabled = sound.getBoolean("enabled", true);
            soundName = Objects.requireNonNullElse(sound.getString("sound", ""), "");
            volume = (float) sound.getDouble("volume", 1.0);
            pitch = (float) sound.getDouble("pitch", 1.0);
        } else if (effectRoot.contains("sound.enabled") || effectRoot.contains("sound.sound")) {
            soundEnabled = effectRoot.getBoolean("sound.enabled", true);
            soundName = Objects.requireNonNullElse(effectRoot.getString("sound.sound", ""), "");
            volume = (float) effectRoot.getDouble("sound.volume", 1.0);
            pitch = (float) effectRoot.getDouble("sound.pitch", 1.0);
        }

        ConfigurationSection particle = effectRoot.getConfigurationSection("particle");
        boolean particleEnabled = false;
        String particleName = "";
        int count = 0;
        double ox = 0.25;
        double oy = 0.3;
        double oz = 0.25;
        if (particle != null) {
            particleEnabled = particle.getBoolean("enabled", true);
            particleName = Objects.requireNonNullElse(particle.getString("particle", "CRIT"), "CRIT");
            count = particle.getInt("count", 8);
            ox = particle.getDouble("offset-x", 0.25);
            oy = particle.getDouble("offset-y", 0.3);
            oz = particle.getDouble("offset-z", 0.25);
        } else if (effectRoot.contains("particle.enabled") || effectRoot.contains("particle.particle")) {
            particleEnabled = effectRoot.getBoolean("particle.enabled", true);
            particleName = Objects.requireNonNullElse(effectRoot.getString("particle.particle", "CRIT"), "CRIT");
            count = effectRoot.getInt("particle.count", 8);
            ox = effectRoot.getDouble("particle.offset-x", 0.25);
            oy = effectRoot.getDouble("particle.offset-y", 0.3);
            oz = effectRoot.getDouble("particle.offset-z", 0.25);
        }

        boolean shouldPlay = soundEnabled || particleEnabled;
        return new EffectPlan(
                shouldPlay,
                soundEnabled,
                soundName,
                volume,
                pitch,
                particleEnabled,
                particleName,
                count,
                ox, oy, oz
        );
    }

    /**
     * Resolve from full config YAML + EffectKey path (e.g. effects.compass.open-gui).
     * Uses dotted-path reads so hyphenated keys like {@code page-turn} always resolve.
     */
    public static EffectPlan resolve(YamlConfiguration config, EffectService.EffectKey key) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(key, "key");
        boolean master = config.getBoolean("effects.enabled", true);
        if (!master) {
            return EffectPlan.disabled();
        }
        String base = key.path();
        // Direct dotted-path resolution (reliable for hyphenated YAML keys)
        if (config.contains(base + ".sound.enabled")
                || config.contains(base + ".sound.sound")
                || config.contains(base + ".particle.enabled")
                || config.contains(base + ".particle.particle")) {
            boolean soundEnabled = config.getBoolean(base + ".sound.enabled", config.contains(base + ".sound.sound"));
            String soundName = Objects.requireNonNullElse(config.getString(base + ".sound.sound", ""), "");
            float volume = (float) config.getDouble(base + ".sound.volume", 1.0);
            float pitch = (float) config.getDouble(base + ".sound.pitch", 1.0);

            boolean particleEnabled = config.getBoolean(base + ".particle.enabled", false);
            // if particle section exists with only particle name, default enabled true unless set
            if (config.contains(base + ".particle.particle") && !config.contains(base + ".particle.enabled")) {
                particleEnabled = true;
            }
            String particleName = Objects.requireNonNullElse(config.getString(base + ".particle.particle", ""), "");
            int count = config.getInt(base + ".particle.count", particleName.isBlank() ? 0 : 8);
            double ox = config.getDouble(base + ".offset-x", config.getDouble(base + ".particle.offset-x", 0.25));
            double oy = config.getDouble(base + ".offset-y", config.getDouble(base + ".particle.offset-y", 0.3));
            double oz = config.getDouble(base + ".offset-z", config.getDouble(base + ".particle.offset-z", 0.25));

            boolean shouldPlay = soundEnabled || particleEnabled;
            return new EffectPlan(
                    shouldPlay, soundEnabled, soundName, volume, pitch,
                    particleEnabled, particleName, count, ox, oy, oz
            );
        }
        return resolve(true, config.getConfigurationSection(base));
    }

    public static boolean isKnownParticleName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.toUpperCase(Locale.ROOT);
        return switch (n) {
            case "ENCHANT", "HAPPY_VILLAGER", "CRIT", "SMOKE", "SOUL",
                 "DAMAGE_INDICATOR", "FLAME", "CLOUD", "END_ROD", "NOTE" -> true;
            default -> n.chars().allMatch(c -> Character.isLetter(c) || c == '_');
        };
    }
}
