package com.fusion.dev.cystol.config;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.config.yaml.ManagedYamlFiles;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe cached config reads. Writes go through comment-preserving SnakeYAML patches
 * (never Bukkit {@code saveConfig()}, which would strip comments).
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
            // Merge jar defaults for any keys added in new plugin versions (comments kept).
            ManagedYamlFiles.update(plugin, "config.yml");
            plugin.reloadConfig();
            this.config = plugin.getConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void loadDefaults() {
        // reload() merges jar defaults (comment-preserving) then loads Bukkit view
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

    /**
     * Arena cuboid for FFA standable search, kill-zone (FFA), and outside-nudge.
     * When both corners share the same Y (common wand mistake), expands vertical span so
     * standable Y search is not empty — see {@link #arenaMinVerticalSpan()}.
     */
    public CuboidBounds arenaCuboid() {
        ConfigurationSection p1 = cfg().getConfigurationSection("arena.pos1");
        ConfigurationSection p2 = cfg().getConfigurationSection("arena.pos2");
        double x1 = p1 != null ? p1.getDouble("x") : 0;
        double y1 = p1 != null ? p1.getDouble("y") : 64;
        double z1 = p1 != null ? p1.getDouble("z") : 0;
        double x2 = p2 != null ? p2.getDouble("x") : 100;
        double y2 = p2 != null ? p2.getDouble("y") : 80;
        double z2 = p2 != null ? p2.getDouble("z") : 100;
        CuboidBounds raw = new CuboidBounds(x1, y1, z1, x2, y2, z2);
        return raw.withMinimumHeight(arenaMinVerticalSpan());
    }

    /**
     * Raw cuboid from config without flat-Y expansion (diagnostics / status).
     */
    public CuboidBounds arenaCuboidRaw() {
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

    /**
     * Minimum vertical span applied when pos1/pos2 are coplanar.
     * Default 24 ≈ pad below floor + air for {@code min-air-above} + y search.
     */
    public double arenaMinVerticalSpan() {
        return Math.max(4.0, cfg().getDouble("arena.min-vertical-span", 24.0));
    }

    /** True when stored pos1/pos2 Y are effectively the same (before auto-expand). */
    public boolean arenaStoredFlat() {
        return arenaCuboidRaw().isVerticallyFlat(0.5);
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

    /**
     * Default bossbar color name when no phase override is set
     * ({@code PINK}/{@code BLUE}/{@code RED}/{@code GREEN}/{@code YELLOW}/{@code PURPLE}/{@code WHITE}).
     */
    public String tabBossbarColor() {
        return normalizeBossBarColorName(cfg().getString("tab.bossbar.color", "RED"), "RED");
    }

    /**
     * Default bossbar style name ({@code PROGRESS}, {@code NOTCHED_6}, …).
     * Paper maps these to Bukkit {@code SOLID}/{@code SEGMENTED_*} in the display layer.
     */
    public String tabBossbarStyle() {
        return normalizeBossBarStyleName(cfg().getString("tab.bossbar.style", "PROGRESS"), "PROGRESS");
    }

    /**
     * Bossbar color for a live phase. Uses {@code tab.bossbar.colors.<phase>} when set,
     * otherwise {@link #tabBossbarColor()}.
     *
     * @param phase event phase (null → default color)
     */
    public String tabBossbarColorForPhase(EventPhase phase) {
        if (phase == null) {
            return tabBossbarColor();
        }
        return tabBossbarColorForKey(phase.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Bossbar color for a display key ({@code countdown}, {@code grace}, {@code hunt}, {@code ffa}, …).
     * Falls back to {@link #tabBossbarColor()} when unset/invalid.
     */
    public String tabBossbarColorForKey(String key) {
        String fallback = tabBossbarColor();
        if (key == null || key.isBlank()) {
            return fallback;
        }
        String raw = cfg().getString("tab.bossbar.colors." + key.trim().toLowerCase(Locale.ROOT), null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return normalizeBossBarColorName(raw, fallback);
    }

    /**
     * Display color: cosmetic grace uses {@code colors.grace}; otherwise phase color.
     */
    public String tabBossbarColorForDisplay(EventPhase phase, boolean graceActive) {
        if (graceActive) {
            return tabBossbarColorForKey("grace");
        }
        return tabBossbarColorForPhase(phase);
    }

    // --- Pre-hunt cosmetic grace (last N seconds of COUNTDOWN) ---

    /** When true and {@link #graceSeconds()} &gt; 0, last N seconds of countdown get grace cosmetics. */
    public boolean graceEnabled() {
        return cfg().getBoolean("grace.enabled", true);
    }

    /**
     * Grace window length in seconds (default 600 = 10 minutes). {@code 0} disables even if enabled is true.
     */
    public long graceSeconds() {
        return Math.max(0L, cfg().getLong("grace.seconds", 600L));
    }

    public void setGraceEnabled(boolean enabled) {
        writeValue("grace.enabled", enabled);
    }

    public void setGraceSeconds(long seconds) {
        writeValue("grace.seconds", Math.max(0L, seconds));
    }

    /** Valid Bukkit/TAB bossbar color names (uppercase). */
    public static final Set<String> BOSSBAR_COLORS = Set.of(
            "PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE"
    );

    /** Canonical style tokens after normalize (uppercase). */
    public static final Set<String> BOSSBAR_STYLES = Set.of(
            "PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20"
    );

    public static String normalizeBossBarColorName(String raw, String fallback) {
        String fb = fallback == null || fallback.isBlank()
                ? "RED"
                : fallback.trim().toUpperCase(Locale.ROOT);
        if (!BOSSBAR_COLORS.contains(fb)) {
            fb = "RED";
        }
        if (raw == null || raw.isBlank()) {
            return fb;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return BOSSBAR_COLORS.contains(u) ? u : fb;
    }

    /**
     * Canonical style tokens stored in memory: {@code PROGRESS} or {@code NOTCHED_6/10/12/20}.
     * Accepts Bukkit {@code SOLID}/{@code SEGMENTED_*} aliases.
     */
    public static String normalizeBossBarStyleName(String raw, String fallback) {
        String fb = canonicalizeStyleToken(
                fallback == null || fallback.isBlank() ? "PROGRESS" : fallback.trim().toUpperCase(Locale.ROOT)
        );
        if (!BOSSBAR_STYLES.contains(fb)) {
            fb = "PROGRESS";
        }
        if (raw == null || raw.isBlank()) {
            return fb;
        }
        String canon = canonicalizeStyleToken(raw.trim().toUpperCase(Locale.ROOT));
        return BOSSBAR_STYLES.contains(canon) ? canon : fb;
    }

    private static String canonicalizeStyleToken(String u) {
        if (u == null || u.isBlank()) {
            return "PROGRESS";
        }
        if ("SOLID".equals(u) || "PROGRESS".equals(u)) {
            return "PROGRESS";
        }
        if (u.startsWith("SEGMENTED_")) {
            return "NOTCHED_" + u.substring("SEGMENTED_".length());
        }
        return u;
    }

    /**
     * Base 0-based index on the existing TAB scoreboard for relative inject lines
     * (entries without an absolute {@code line} key).
     */
    public int scoreboardOffset() {
        return Math.max(0, cfg().getInt("tab.scoreboard.offset", 3));
    }

    /**
     * How many leaderboard slots to expose as {@code %topN_name%} / {@code %topN_kills%}
     * (and dense {@code %topN_place%}). Default 3.
     */
    public int scoreboardTopSlots() {
        return Math.max(1, Math.min(15, cfg().getInt("tab.scoreboard.top-slots", 3)));
    }

    /** Placeholder value when a top-N slot has no killer yet. */
    public String scoreboardEmptyName() {
        String v = cfg().getString("tab.scoreboard.empty-name", "—");
        return v == null || v.isBlank() ? "—" : v;
    }

    public String scoreboardEmptyKills() {
        String v = cfg().getString("tab.scoreboard.empty-kills", "0");
        return v == null ? "0" : v;
    }

    /**
     * Lines to inject into the existing TAB board — never a replacement scoreboard.
     *
     * <p>Each entry is either:
     * <ul>
     *   <li>a plain string → placed at {@code offset + relativeIndex}</li>
     *   <li>a map with {@code text} and optional absolute {@code line} (0-based)</li>
     * </ul>
     * Relative entries only consume the relative counter; absolute {@code line} overrides
     * do not shift following relative slots.
     */
    public List<ScoreboardLine> scoreboardLines() {
        List<ScoreboardLine> lines = new ArrayList<>();
        int offset = scoreboardOffset();
        List<?> raw = cfg().getList("tab.scoreboard.lines");
        if (raw == null || raw.isEmpty()) {
            // Legacy: map-list only configs
            raw = cfg().getMapList("tab.scoreboard.lines");
        }
        if (raw == null) {
            return Collections.unmodifiableList(lines);
        }
        int relative = 0;
        for (Object o : raw) {
            if (o instanceof String s) {
                if (!s.isBlank()) {
                    lines.add(new ScoreboardLine(offset + relative, s));
                }
                relative++;
                continue;
            }
            if (o instanceof java.util.Map<?, ?> map) {
                Object textObj = map.get("text");
                if (textObj == null) {
                    // bare string-like values sometimes land as single-key maps; skip empty
                    continue;
                }
                String text = String.valueOf(textObj);
                Object lineObj = map.get("line");
                if (lineObj instanceof Number n) {
                    int idx = n.intValue();
                    if (idx >= 0) {
                        lines.add(new ScoreboardLine(idx, text));
                    }
                    // absolute line does not advance relative counter
                } else {
                    lines.add(new ScoreboardLine(offset + relative, text));
                    relative++;
                }
            }
        }
        return Collections.unmodifiableList(lines);
    }

    /** Target chord (block) between adjacent FFA ring mates; ring shrinks for few players. */
    public double minPlayerSpacing() {
        return Math.max(1.0, cfg().getDouble("ffa.min-player-spacing", 8.0));
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
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("arena.pos1.x", x);
        values.put("arena.pos1.y", y);
        values.put("arena.pos1.z", z);
        writeValues(values);
    }

    public void setPos2(double x, double y, double z) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("arena.pos2.x", x);
        values.put("arena.pos2.y", y);
        values.put("arena.pos2.z", z);
        writeValues(values);
    }

    public void setCenter(String world, double x, double y, double z, float yaw, float pitch) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("arena.world", world);
        values.put("arena.centerspawn.x", x);
        values.put("arena.centerspawn.y", y);
        values.put("arena.centerspawn.z", z);
        values.put("arena.centerspawn.yaw", (double) yaw);
        values.put("arena.centerspawn.pitch", (double) pitch);
        writeValues(values);
    }

    public void setArenaWorld(String world) {
        writeString("arena.world", world);
    }

    private void writeString(String path, String value) {
        writeValue(path, value);
    }

    private void writeValue(String path, Object value) {
        Map<String, Object> single = new LinkedHashMap<>();
        single.put(path, value);
        writeValues(single);
    }

    /**
     * Comment-preserving multi-key write, then refresh the Bukkit view.
     */
    private void writeValues(Map<String, ?> pathValues) {
        lock.writeLock().lock();
        try {
            ManagedYamlFiles.patchConfig(plugin, pathValues);
            plugin.reloadConfig();
            this.config = plugin.getConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setRewardsEnabled(boolean enabled) {
        writeValue("rewards.enabled", enabled);
    }

    public void setRewardsMaxPlace(int maxPlace) {
        writeValue("rewards.max-place", Math.max(0, maxPlace));
    }

    public void setFfaBeforeEndSeconds(long seconds) {
        writeValue("ffa.before-end-seconds", Math.max(0L, seconds));
    }

    public void setAnnounceLeadSeconds(long seconds) {
        writeValue("ffa.announce-lead-seconds", Math.max(0L, seconds));
    }

    public void setAnnounceIntervalSeconds(long seconds) {
        writeValue("ffa.announce-interval-seconds", Math.max(1L, seconds));
    }

    public void setOutsideActionbarSeconds(long seconds) {
        writeValue("ffa.outside-actionbar-seconds", Math.max(1L, seconds));
    }

    public void setFfaFinalCountdownEnabled(boolean enabled) {
        writeValue("ffa.final-countdown.enabled", enabled);
    }

    public void setFfaFinalCountdownFromSeconds(int from) {
        writeValue("ffa.final-countdown.from-seconds", Math.max(0, from));
    }

    public void setFfaFinalCountdownAudience(String audience) {
        String v = audience == null ? "all" : audience.trim().toLowerCase(java.util.Locale.ROOT);
        if (!v.equals("all") && !v.equals("eligible")) {
            v = "all";
        }
        writeValue("ffa.final-countdown.audience", v);
    }

    public void setMinPlayerSpacing(double spacing) {
        writeValue("ffa.min-player-spacing", Math.max(1.0, spacing));
    }

    public void setTabBossbarEnabled(boolean enabled) {
        writeValue("tab.bossbar.enabled", enabled);
    }

    // --- Teleport lock (Hunt only) ---

    public boolean teleportLockEnabled() {
        return cfg().getBoolean("teleport-lock.enabled", true);
    }

    /**
     * Lowercase command labels blocked during hunt when outside spawn/arena
     * (e.g. {@code spawn}, {@code home}). Plugin prefixes are stripped at match time.
     */
    public java.util.Set<String> teleportLockCommands() {
        java.util.List<String> raw = cfg().getStringList("teleport-lock.commands");
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            for (String d : DEFAULT_TP_LOCK_COMMANDS) {
                out.add(d);
            }
            return java.util.Collections.unmodifiableSet(out);
        }
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            out.add(s.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private static final String[] DEFAULT_TP_LOCK_COMMANDS = {
            "spawn", "home", "homes", "back", "rtp", "wild", "tpa", "tpahere",
            "tpaccept", "tpyes", "tpdeny", "tno", "warp", "warps", "top",
            "tp", "tpo", "tphere", "call", "bring", "etp", "espawn", "ehome", "eback"
    };

    public String spawnWorld() {
        return cfg().getString("spawn.world", "world");
    }

    /**
     * False until admins set both corners (or config has non-zero-ish defaults intentionally set).
     * We treat missing section as unconfigured so only arena is a free zone until spawn is set.
     */
    public boolean spawnZoneConfigured() {
        ConfigurationSection p1 = cfg().getConfigurationSection("spawn.pos1");
        ConfigurationSection p2 = cfg().getConfigurationSection("spawn.pos2");
        if (p1 == null || p2 == null) {
            return false;
        }
        // Explicit opt-in flag preferred; fall back to "both corners present"
        if (cfg().isSet("spawn.configured")) {
            return cfg().getBoolean("spawn.configured", false);
        }
        return true;
    }

    public CuboidBounds spawnCuboid() {
        ConfigurationSection p1 = cfg().getConfigurationSection("spawn.pos1");
        ConfigurationSection p2 = cfg().getConfigurationSection("spawn.pos2");
        double x1 = p1 != null ? p1.getDouble("x") : 0;
        double y1 = p1 != null ? p1.getDouble("y") : 64;
        double z1 = p1 != null ? p1.getDouble("z") : 0;
        double x2 = p2 != null ? p2.getDouble("x") : 0;
        double y2 = p2 != null ? p2.getDouble("y") : 64;
        double z2 = p2 != null ? p2.getDouble("z") : 0;
        return new CuboidBounds(x1, y1, z1, x2, y2, z2);
    }

    public void setSpawnWorld(String world) {
        writeString("spawn.world", world);
    }

    public void setSpawnPos1(double x, double y, double z) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spawn.pos1.x", x);
        values.put("spawn.pos1.y", y);
        values.put("spawn.pos1.z", z);
        values.put("spawn.configured", true);
        writeValues(values);
    }

    public void setSpawnPos2(double x, double y, double z) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spawn.pos2.x", x);
        values.put("spawn.pos2.y", y);
        values.put("spawn.pos2.z", z);
        values.put("spawn.configured", true);
        writeValues(values);
    }

    public ConfigurationSection effectSection(String path) {
        return cfg().getConfigurationSection(path);
    }
}
