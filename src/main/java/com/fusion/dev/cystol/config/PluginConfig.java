package com.fusion.dev.cystol.config;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe cached config reads. Writes go through synchronized save helpers.
 */
public final class PluginConfig {

    public record ScoreboardLine(int line, String text) {
    }

    private final JavaPlugin plugin;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private FileConfiguration config;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        lock.writeLock().lock();
        try {
            plugin.reloadConfig();
            this.config = plugin.getConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void loadDefaults() {
        plugin.saveDefaultConfig();
        reload();
    }

    private FileConfiguration cfg() {
        lock.readLock().lock();
        try {
            if (config == null) {
                throw new IllegalStateException("Config not loaded");
            }
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }

    public long ffaBeforeEndSeconds() {
        return cfg().getLong("ffa.before-end-seconds", 1800L);
    }

    public long announceLeadSeconds() {
        return cfg().getLong("ffa.announce-lead-seconds", 3600L);
    }

    public long announceIntervalSeconds() {
        return cfg().getLong("ffa.announce-interval-seconds", 600L);
    }

    public long outsideActionbarSeconds() {
        return cfg().getLong("ffa.outside-actionbar-seconds", 60L);
    }

    /** Last-seconds 5…1 title countdown before FFA ring TP. */
    public boolean ffaFinalCountdownEnabled() {
        return cfg().getBoolean("ffa.final-countdown.enabled", true);
    }

    /**
     * Highest digit shown (5 → 5,4,3,2,1). {@code 0} disables even if enabled is true.
     */
    public int ffaFinalCountdownFromSeconds() {
        return Math.max(0, cfg().getInt("ffa.final-countdown.from-seconds", 5));
    }

    /**
     * Who sees the final countdown titles/sounds: {@code all} (default) or {@code eligible}
     * (same rules as FFA TP: survival, unvanished, no bypass).
     */
    public String ffaFinalCountdownAudience() {
        String v = cfg().getString("ffa.final-countdown.audience", "all");
        return v == null ? "all" : v.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public int ffaFinalCountdownFadeInMs() {
        return Math.max(0, cfg().getInt("ffa.final-countdown.title-fade-in-ms", 0));
    }

    public int ffaFinalCountdownStayMs() {
        return Math.max(50, cfg().getInt("ffa.final-countdown.title-stay-ms", 800));
    }

    public int ffaFinalCountdownFadeOutMs() {
        return Math.max(0, cfg().getInt("ffa.final-countdown.title-fade-out-ms", 150));
    }

    public float ffaFinalCountdownPitchBase() {
        return (float) cfg().getDouble("ffa.final-countdown.pitch-base", 0.9);
    }

    /** Added per step as remaining seconds drop (5→4→…→1). */
    public float ffaFinalCountdownPitchStep() {
        return (float) cfg().getDouble("ffa.final-countdown.pitch-step", 0.12);
    }

    public double maxDiameterFraction() {
        return cfg().getDouble("ffa.max-diameter-fraction", 0.75);
    }

    public int minAirAbove() {
        return cfg().getInt("ffa.min-air-above", 2);
    }

    public int ySearchRange() {
        return cfg().getInt("ffa.y-search-range", 12);
    }

    public double ringMarginBlocks() {
        return cfg().getDouble("ffa.ring-margin-blocks", 2.0);
    }

    public String storageFile() {
        return cfg().getString("storage.file", "data.db");
    }

    /** Ceremony reward chat messages for top dense-rank places. */
    public boolean rewardsEnabled() {
        return cfg().getBoolean("rewards.enabled", true);
    }

    /**
     * Highest dense-rank place that receives a reward message (1 = place 1 only; 3 = places 1–3).
     * Ties share place. {@code 0} disables eligibility even if enabled is true.
     */
    public int rewardsMaxPlace() {
        return Math.max(0, cfg().getInt("rewards.max-place", 3));
    }

    public String arenaWorld() {
        return cfg().getString("arena.world", "world");
    }

    public CuboidBounds arenaCuboid() {
        ConfigurationSection p1 = cfg().getConfigurationSection("arena.pos1");
        ConfigurationSection p2 = cfg().getConfigurationSection("arena.pos2");
        double x1 = p1 != null ? p1.getDouble("x") : 0;
        double y1 = p1 != null ? p1.getDouble("y") : 64;
        double z1 = p1 != null ? p1.getDouble("z") : 0;
        double x2 = p2 != null ? p2.getDouble("x") : 100;
        double y2 = p2 != null ? p2.getDouble("y") : 80;
        double z2 = p2 != null ? p2.getDouble("z") : 100;
        return new CuboidBounds(x1, y1, z1, x2, y2, z2);
    }

    public double centerX() {
        return cfg().getDouble("arena.centerspawn.x", 50.5);
    }

    public double centerY() {
        return cfg().getDouble("arena.centerspawn.y", 65.0);
    }

    public double centerZ() {
        return cfg().getDouble("arena.centerspawn.z", 50.5);
    }

    public float centerYaw() {
        return (float) cfg().getDouble("arena.centerspawn.yaw", 0.0);
    }

    public float centerPitch() {
        return (float) cfg().getDouble("arena.centerspawn.pitch", 0.0);
    }

    public String compassMaterial() {
        return cfg().getString("compass.material", "COMPASS");
    }

    public int compassCmd() {
        return cfg().getInt("compass.custom-model-data", 0);
    }

    public int fullInvActionbarSeconds() {
        return cfg().getInt("compass.full-inv-actionbar-seconds", 60);
    }

    public String wandMaterial() {
        return cfg().getString("wand.material", "BLAZE_ROD");
    }

    public int wandCmd() {
        return cfg().getInt("wand.custom-model-data", 0);
    }

    public boolean effectsEnabled() {
        return cfg().getBoolean("effects.enabled", true);
    }

    public boolean tabBossbarEnabled() {
        return cfg().getBoolean("tab.bossbar.enabled", true);
    }

    public List<ScoreboardLine> scoreboardLines() {
        List<ScoreboardLine> lines = new ArrayList<>();
        List<?> raw = cfg().getMapList("tab.scoreboard.lines");
        for (Object o : raw) {
            if (o instanceof java.util.Map<?, ?> map) {
                Object lineObj = map.get("line");
                Object textObj = map.get("text");
                if (lineObj instanceof Number n && textObj != null) {
                    lines.add(new ScoreboardLine(n.intValue(), String.valueOf(textObj)));
                }
            }
        }
        return Collections.unmodifiableList(lines);
    }

    public Optional<Instant> configuredStart() {
        return TimeUtil.parseUtc(cfg().getString("times.start", ""));
    }

    public Optional<Instant> configuredEnd() {
        return TimeUtil.parseUtc(cfg().getString("times.end", ""));
    }

    public Optional<Instant> configuredFfaOverride() {
        return TimeUtil.parseUtc(cfg().getString("times.ffa", ""));
    }

    public void setTimeStart(Instant instant) {
        writeString("times.start", instant == null ? "" : TimeUtil.formatUtc(instant));
    }

    public void setTimeEnd(Instant instant) {
        writeString("times.end", instant == null ? "" : TimeUtil.formatUtc(instant));
    }

    public void setTimeFfa(Instant instant) {
        writeString("times.ffa", instant == null ? "" : TimeUtil.formatUtc(instant));
    }

    public void setPos1(double x, double y, double z) {
        lock.writeLock().lock();
        try {
            config.set("arena.pos1.x", x);
            config.set("arena.pos1.y", y);
            config.set("arena.pos1.z", z);
            plugin.saveConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setPos2(double x, double y, double z) {
        lock.writeLock().lock();
        try {
            config.set("arena.pos2.x", x);
            config.set("arena.pos2.y", y);
            config.set("arena.pos2.z", z);
            plugin.saveConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setCenter(String world, double x, double y, double z, float yaw, float pitch) {
        lock.writeLock().lock();
        try {
            config.set("arena.world", world);
            config.set("arena.centerspawn.x", x);
            config.set("arena.centerspawn.y", y);
            config.set("arena.centerspawn.z", z);
            config.set("arena.centerspawn.yaw", yaw);
            config.set("arena.centerspawn.pitch", pitch);
            plugin.saveConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setArenaWorld(String world) {
        writeString("arena.world", world);
    }

    private void writeString(String path, String value) {
        lock.writeLock().lock();
        try {
            config.set(path, value);
            plugin.saveConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ConfigurationSection effectSection(String path) {
        return cfg().getConfigurationSection(path);
    }
}
