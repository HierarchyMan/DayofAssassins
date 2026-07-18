package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventTimeline;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.util.TimeUtil;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.bossbar.BarColor;
import me.neznamy.tab.api.bossbar.BarStyle;
import me.neznamy.tab.api.bossbar.BossBar;
import me.neznamy.tab.api.bossbar.BossBarManager;
import me.neznamy.tab.api.scoreboard.Line;
import me.neznamy.tab.api.scoreboard.Scoreboard;
import me.neznamy.tab.api.scoreboard.ScoreboardManager;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarFlag;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TAB hard-depend for scoreboard line injection; event bossbar prefers TAB when its
 * bossbar feature is loaded, otherwise uses Paper's built-in {@link org.bukkit.boss.BossBar}
 * (server API — not shaded; available on every Paper runtime).
 *
 * <p>Does <strong>not</strong> call {@code showScoreboard} — that would replace the player's
 * normal TAB layout. Instead each configured row is written via {@link Line#setText(String)}
 * and restored on {@link #clear()}.
 */
public final class TabDisplayService {

    private static final String BOSSBAR_TITLE_SEED = "Day of Assassins";

    /** Which transport owns the live event bossbar. */
    private enum BossBackend {
        NONE,
        TAB,
        PAPER
    }

    private final EventManager eventManager;
    private final KillService killService;
    private final PluginConfig config;
    private final Lang lang;
    private final Logger logger;

    private BossBackend bossBackend = BossBackend.NONE;
    /** TAB API bar (only when {@link BossBackend#TAB}). */
    private BossBar tabBossBar;
    /** Paper/Bukkit bar (only when {@link BossBackend#PAPER}). Progress is 0.0–1.0. */
    private org.bukkit.boss.BossBar paperBossBar;
    private boolean available = true;
    private boolean active;

    private String lastBossTitle;
    private float lastBossProgress = Float.NaN;
    private String lastScoreboardFingerprint;
    /** Throttle "bossbar missing" log — never spam once per second. */
    private long lastBossMissingLogMs;
    /** Online player count last time we scanned for bossbar viewers (join/leave delta). */
    private int lastBossViewerScanSize = -1;

    /**
     * Original line text restored on clear: scoreboard name → (line index → original text).
     */
    private final Map<String, Map<Integer, String>> savedScoreboardLines = new ConcurrentHashMap<>();

    public TabDisplayService(
            EventManager eventManager,
            KillService killService,
            PluginConfig config,
            Lang lang,
            Logger logger
    ) {
        this.eventManager = eventManager;
        this.killService = killService;
        this.config = config;
        this.lang = lang;
        this.logger = logger;
    }

    public void init() {
        try {
            TabAPI api = TabAPI.getInstance();
            if (api == null) {
                available = false;
                logger.severe("TAB API instance is null");
                // Still try Paper bossbar — event UI should not die solely on TAB absence here
                // (hard-depend usually prevents this path; defensive for partial loads).
                if (config.tabBossbarEnabled()) {
                    ensurePaperBossBar();
                }
                return;
            }
            // Scoreboard manager may be null if that TAB feature is off.
            ensureBossBar(api);
            if (api.getScoreboardManager() == null) {
                logger.warning("TAB ScoreboardManager is null — scoreboard line inject disabled "
                        + "(enable scoreboard feature in TAB config)");
            }
            if (config.tabBossbarEnabled() && bossBackend == BossBackend.NONE) {
                logger.warning("Event bossbar failed to create on TAB and Paper backends");
            } else if (config.tabBossbarEnabled() && bossBackend == BossBackend.PAPER) {
                logger.info("Event bossbar using Paper/Bukkit API "
                        + "(TAB BossBarManager unavailable — bossbar feature likely off in TAB config)");
            }
        } catch (Throwable t) {
            available = false;
            logger.log(Level.SEVERE, "Failed to init TAB display", t);
            if (config.tabBossbarEnabled()) {
                try {
                    ensurePaperBossBar();
                } catch (Throwable ignored) {
                    // already logged in ensure
                }
            }
        }
    }

    public void update(Instant now) {
        // Bossbar can run on Paper even if TAB scoreboard path is down; scoreboard still needs TAB.
        if (!available && bossBackend == BossBackend.NONE && !config.tabBossbarEnabled()) {
            return;
        }
        try {
            EventTimeline timeline = eventManager.timeline();
            EventPhase phase = timeline.phaseAt(now);
            int topSlots = config.scoreboardTopSlots();
            List<DenseRanking.Entry> topRanking = killService.top(topSlots);
            Optional<DenseRanking.Entry> top1 = topRanking.isEmpty()
                    ? Optional.empty()
                    : Optional.of(topRanking.getFirst());
            String topName = top1.map(DenseRanking.Entry::name).orElse(null);
            Integer topKills = top1.map(DenseRanking.Entry::kills).orElse(null);

            EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                    timeline,
                    now,
                    config.announceLeadSeconds(),
                    lang.raw("bossbar.countdown-title"),
                    lang.raw("bossbar.title"),
                    lang.raw("bossbar.title-no-kills"),
                    topName,
                    topKills
            );

            active = true;
            updateBossBar(bar);
            if (available) {
                TabAPI api = TabAPI.getInstance();
                if (api != null) {
                    updateScoreboardInject(api, phase, timeline, now, topRanking, topSlots);
                }
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "display update failed", t);
        }
    }

    private void updateBossBar(EventDisplayRenderer.BossBarView bar) {
        if (!config.tabBossbarEnabled()) {
            return;
        }
        ensureBossBar(TabAPI.getInstance());
        if (bossBackend == BossBackend.NONE) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastBossMissingLogMs > 60_000L) {
                lastBossMissingLogMs = nowMs;
                logger.warning("Event bossbar missing after ensure (TAB and Paper backends failed)");
            }
            return;
        }
        try {
            String title = EventDisplayRenderer.colorAmpersandToSection(bar.titleLegacyAmpersand());
            if (bossBackend == BossBackend.TAB) {
                updateTabBossBar(title, bar.progress());
            } else {
                updatePaperBossBar(title, bar.progress());
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "bossbar update failed — recreating", t);
            destroyBossBars();
            lastBossTitle = null;
            lastBossProgress = Float.NaN;
            lastBossViewerScanSize = -1;
        }
    }

    private void updateTabBossBar(String titleSection, float progress01) {
        if (tabBossBar == null) {
            return;
        }
        TabAPI api = TabAPI.getInstance();
        // TAB bossbar progress is 0–100 (client receives value/100).
        float progressPercent = EventDisplayRenderer.progressPercent(progress01);
        if (!titleSection.equals(lastBossTitle)) {
            tabBossBar.setTitle(titleSection);
            lastBossTitle = titleSection;
        }
        if (Float.isNaN(lastBossProgress) || Math.abs(progressPercent - lastBossProgress) > 0.05f) {
            tabBossBar.setProgress(progressPercent);
            lastBossProgress = progressPercent;
        }
        int online = Bukkit.getOnlinePlayers().size();
        int viewers;
        try {
            viewers = tabBossBar.getPlayers().size();
        } catch (Throwable t) {
            viewers = -1;
        }
        boolean needScan = viewers == 0 || viewers != online || online != lastBossViewerScanSize;
        if (!needScan) {
            return;
        }
        lastBossViewerScanSize = online;
        if (api == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            TabPlayer tp = api.getPlayer(p.getUniqueId());
            if (tp == null) {
                continue;
            }
            try {
                if (!tabBossBar.containsPlayer(tp)) {
                    tabBossBar.addPlayer(tp);
                }
            } catch (Throwable addEx) {
                logger.log(Level.FINE, "TAB bossbar addPlayer failed for " + p.getName(), addEx);
            }
        }
    }

    private void updatePaperBossBar(String titleSection, float progress01) {
        if (paperBossBar == null) {
            return;
        }
        // Bukkit bossbar progress is 0.0–1.0.
        float progress = Math.max(0f, Math.min(1f, progress01));
        if (!titleSection.equals(lastBossTitle)) {
            paperBossBar.setTitle(titleSection);
            lastBossTitle = titleSection;
        }
        if (Float.isNaN(lastBossProgress) || Math.abs(progress - lastBossProgress) > 0.0005f) {
            paperBossBar.setProgress(progress);
            lastBossProgress = progress;
        }
        paperBossBar.setVisible(true);
        int online = Bukkit.getOnlinePlayers().size();
        int viewers = paperBossBar.getPlayers().size();
        boolean needScan = viewers == 0 || viewers != online || online != lastBossViewerScanSize;
        if (!needScan) {
            return;
        }
        lastBossViewerScanSize = online;
        Set<UUID> want = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            want.add(p.getUniqueId());
            if (!paperBossBar.getPlayers().contains(p)) {
                paperBossBar.addPlayer(p);
            }
        }
        for (Player p : new ArrayList<>(paperBossBar.getPlayers())) {
            if (!want.contains(p.getUniqueId())) {
                paperBossBar.removePlayer(p);
            }
        }
    }

    private void updateScoreboardInject(
            TabAPI api,
            EventPhase phase,
            EventTimeline timeline,
            Instant now,
            List<DenseRanking.Entry> topRanking,
            int topSlots
    ) {
        ScoreboardManager sbm = api.getScoreboardManager();
        if (sbm == null) {
            return;
        }
        List<PluginConfig.ScoreboardLine> configured = config.scoreboardLines();
        if (configured == null || configured.isEmpty()) {
            return;
        }

        // %remaining% = until next phase (hunt→ffa, ffa→end). %until_end% still available in templates.
        String remainingPhase = TimeUtil.formatCountdown(timeline.secondsUntilNextPhase(now));
        String remainingEnd = TimeUtil.formatCountdown(timeline.secondsUntilEnd(now));
        String phaseLabel = lang.raw("phase." + phase.name().toLowerCase(), phase.name());
        Map<Integer, String> injections = EventDisplayRenderer.renderScoreboardInjections(
                configured,
                phase,
                phaseLabel,
                remainingPhase,
                remainingEnd,
                topRanking,
                topSlots,
                config.scoreboardEmptyName(),
                config.scoreboardEmptyKills()
        );
        if (injections.isEmpty()) {
            return;
        }

        String fingerprint = scoreboardFingerprint(phase, topRanking, injections);
        boolean contentChanged = !fingerprint.equals(lastScoreboardFingerprint);

        // Fast path: text unchanged — only inject boards we have never touched (new join / board switch).
        if (!contentChanged && !savedScoreboardLines.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                TabPlayer tp = api.getPlayer(p.getUniqueId());
                if (tp == null) {
                    continue;
                }
                try {
                    Scoreboard activeSb = sbm.getActiveScoreboard(tp);
                    if (activeSb == null) {
                        continue;
                    }
                    String name = activeSb.getName();
                    String key = name == null ? ("active:" + p.getUniqueId()) : name;
                    if (!savedScoreboardLines.containsKey(key)) {
                        injectLines(key, activeSb, injections);
                    }
                } catch (Throwable ignored) {
                    // mid-load
                }
            }
            return;
        }

        // Content changed (or first inject ever): full registered + active board pass.
        Map<String, Scoreboard> targets = new HashMap<>();
        try {
            Map<String, Scoreboard> registered = sbm.getRegisteredScoreboards();
            if (registered != null) {
                for (Map.Entry<String, Scoreboard> e : registered.entrySet()) {
                    if (e.getValue() != null) {
                        targets.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "list registered scoreboards failed", t);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            TabPlayer tp = api.getPlayer(p.getUniqueId());
            if (tp == null) {
                continue;
            }
            try {
                Scoreboard activeSb = sbm.getActiveScoreboard(tp);
                if (activeSb != null) {
                    String name = activeSb.getName();
                    targets.put(name == null ? ("active:" + p.getUniqueId()) : name, activeSb);
                }
            } catch (Throwable ignored) {
                // player not fully loaded in TAB yet
            }
        }

        for (Map.Entry<String, Scoreboard> e : targets.entrySet()) {
            boolean firstTouch = !savedScoreboardLines.containsKey(e.getKey());
            if (contentChanged || firstTouch) {
                injectLines(e.getKey(), e.getValue(), injections);
            }
        }
        lastScoreboardFingerprint = fingerprint;
    }

    /**
     * Write event lines into {@code board} at configured indices only. Saves original text once
     * so {@link #clear()} can restore. Does not replace the board or other rows.
     */
    private void injectLines(
            String boardKey,
            Scoreboard board,
            Map<Integer, String> injections
    ) {
        if (board == null || injections.isEmpty()) {
            return;
        }
        List<Line> lines;
        try {
            lines = board.getLines();
        } catch (Throwable t) {
            logger.log(Level.FINE, "scoreboard getLines failed for " + boardKey, t);
            return;
        }
        if (lines == null || lines.isEmpty()) {
            return;
        }

        Map<Integer, String> originals = savedScoreboardLines.computeIfAbsent(
                boardKey, k -> new ConcurrentHashMap<>()
        );

        for (Map.Entry<Integer, String> inj : injections.entrySet()) {
            int idx = inj.getKey();
            if (idx < 0 || idx >= lines.size()) {
                continue;
            }
            Line line = lines.get(idx);
            if (line == null) {
                continue;
            }
            try {
                // Snapshot original the first time we touch this cell (pre-event text / placeholders).
                originals.computeIfAbsent(idx, i -> {
                    try {
                        String raw = line.getText();
                        return raw == null ? "" : raw;
                    } catch (Throwable t) {
                        return "";
                    }
                });
                String next = inj.getValue() == null || inj.getValue().isBlank() ? " " : inj.getValue();
                String current;
                try {
                    current = line.getText();
                } catch (Throwable t) {
                    current = null;
                }
                if (next.equals(current)) {
                    continue;
                }
                line.setText(next);
            } catch (Throwable t) {
                logger.log(Level.FINE, "scoreboard inject line " + idx + " on " + boardKey + " failed", t);
            }
        }
    }

    private static String scoreboardFingerprint(
            EventPhase phase,
            List<DenseRanking.Entry> topRanking,
            Map<Integer, String> injections
    ) {
        StringBuilder b = new StringBuilder(96);
        b.append(phase == null ? "IDLE" : phase.name()).append('|');
        if (topRanking != null) {
            for (DenseRanking.Entry e : topRanking) {
                b.append(e.name()).append('#').append(e.kills()).append('#').append(e.place()).append(';');
            }
        }
        b.append('|');
        for (Map.Entry<Integer, String> e : injections.entrySet()) {
            b.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue()).append('\n');
        }
        return b.toString();
    }

    /**
     * Create event bossbar if missing. Prefers TAB (stacks with existing TAB bars) when
     * {@link BossBarManager} is loaded; otherwise Paper/Bukkit {@link org.bukkit.boss.BossBar}.
     */
    private void ensureBossBar(TabAPI api) {
        if (bossBackend != BossBackend.NONE || !config.tabBossbarEnabled()) {
            return;
        }
        if (api != null) {
            BossBarManager bbm = api.getBossBarManager();
            if (bbm != null) {
                try {
                    tabBossBar = createEventBossBar(bbm);
                    bossBackend = BossBackend.TAB;
                    lastBossTitle = null;
                    lastBossProgress = Float.NaN;
                    logger.info("Created Day of Assassins TAB bossbar (stacks with existing bars)");
                    return;
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Failed to create TAB bossbar — trying Paper", t);
                    tabBossBar = null;
                }
            }
        }
        ensurePaperBossBar();
    }

    private void ensurePaperBossBar() {
        if (bossBackend != BossBackend.NONE || !config.tabBossbarEnabled()) {
            return;
        }
        try {
            // Paper ships org.bukkit.boss.BossBar — no third-party shade required.
            paperBossBar = Bukkit.createBossBar(
                    BOSSBAR_TITLE_SEED,
                    org.bukkit.boss.BarColor.RED,
                    org.bukkit.boss.BarStyle.SOLID
            );
            paperBossBar.setProgress(0.0);
            paperBossBar.setVisible(true);
            // Keep fog/sky clean — event bar is informational only.
            paperBossBar.removeFlag(BarFlag.CREATE_FOG);
            paperBossBar.removeFlag(BarFlag.DARKEN_SKY);
            paperBossBar.removeFlag(BarFlag.PLAY_BOSS_MUSIC);
            bossBackend = BossBackend.PAPER;
            lastBossTitle = null;
            lastBossProgress = Float.NaN;
            logger.info("Created Day of Assassins Paper/Bukkit bossbar (TAB bossbar feature not required)");
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to create Paper bossbar", t);
            paperBossBar = null;
            bossBackend = BossBackend.NONE;
        }
    }

    private static BossBar createEventBossBar(BossBarManager bbm) {
        // TAB 5.x: createBossBar(title, progress 0-100, color, style) — generates UUID name.
        // Progress seed 0; live updates use setProgress with 0–100.
        return bbm.createBossBar(BOSSBAR_TITLE_SEED, 0f, BarColor.RED, BarStyle.PROGRESS);
    }

    private void destroyBossBars() {
        hideBossBarFromEveryone();
        if (paperBossBar != null) {
            try {
                paperBossBar.setVisible(false);
                paperBossBar.removeAll();
            } catch (Throwable ignored) {
                // best-effort
            }
            paperBossBar = null;
        }
        tabBossBar = null;
        bossBackend = BossBackend.NONE;
    }

    public void clear() {
        // Cheap no-op when already idle — tick must not pay restore/hide cost every second.
        if (!active && savedScoreboardLines.isEmpty()) {
            boolean barHasViewers = false;
            if (bossBackend == BossBackend.TAB && tabBossBar != null) {
                try {
                    barHasViewers = !tabBossBar.getPlayers().isEmpty();
                } catch (Throwable ignored) {
                    barHasViewers = false;
                }
            } else if (bossBackend == BossBackend.PAPER && paperBossBar != null) {
                try {
                    barHasViewers = !paperBossBar.getPlayers().isEmpty();
                } catch (Throwable ignored) {
                    barHasViewers = false;
                }
            }
            if (!barHasViewers) {
                return;
            }
        }
        active = false;
        lastBossTitle = null;
        lastBossProgress = Float.NaN;
        lastScoreboardFingerprint = null;
        lastBossViewerScanSize = -1;

        try {
            hideBossBarFromEveryone();
            if (available) {
                restoreScoreboardLines(TabAPI.getInstance());
            } else {
                savedScoreboardLines.clear();
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "display clear failed", t);
            savedScoreboardLines.clear();
        }
    }

    /** Pull the event bar from all viewers without destroying the bar registration. */
    private void hideBossBarFromEveryone() {
        if (bossBackend == BossBackend.TAB && tabBossBar != null) {
            try {
                for (TabPlayer tp : new ArrayList<>(tabBossBar.getPlayers())) {
                    try {
                        tabBossBar.removePlayer(tp);
                    } catch (Throwable ignored) {
                        // continue others
                    }
                }
            } catch (Throwable t) {
                logger.log(Level.FINE, "TAB bossbar hide failed", t);
            }
            return;
        }
        if (bossBackend == BossBackend.PAPER && paperBossBar != null) {
            try {
                paperBossBar.removeAll();
                paperBossBar.setVisible(false);
            } catch (Throwable t) {
                logger.log(Level.FINE, "Paper bossbar hide failed", t);
            }
        }
    }

    /**
     * Force an immediate UI push (e.g. right after unpause). Safe if not in a display phase —
     * callers should only invoke when the event is live.
     */
    public void forceRefresh(Instant now) {
        if (now == null) {
            now = Instant.now();
        }
        update(now);
    }

    private void restoreScoreboardLines(TabAPI api) {
        if (savedScoreboardLines.isEmpty()) {
            return;
        }
        ScoreboardManager sbm = api == null ? null : api.getScoreboardManager();
        Map<String, Scoreboard> byName = new HashMap<>();
        if (sbm != null) {
            try {
                Map<String, Scoreboard> registered = sbm.getRegisteredScoreboards();
                if (registered != null) {
                    byName.putAll(registered);
                }
            } catch (Throwable ignored) {
                // restore best-effort
            }
            if (api != null) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    TabPlayer tp = api.getPlayer(p.getUniqueId());
                    if (tp == null) {
                        continue;
                    }
                    try {
                        Scoreboard activeSb = sbm.getActiveScoreboard(tp);
                        if (activeSb != null && activeSb.getName() != null) {
                            byName.putIfAbsent(activeSb.getName(), activeSb);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }

        for (Map.Entry<String, Map<Integer, String>> boardEntry : savedScoreboardLines.entrySet()) {
            String key = boardEntry.getKey();
            Scoreboard board = byName.get(key);
            if (board == null) {
                continue;
            }
            List<Line> lines;
            try {
                lines = board.getLines();
            } catch (Throwable t) {
                continue;
            }
            if (lines == null) {
                continue;
            }
            for (Map.Entry<Integer, String> lineEntry : boardEntry.getValue().entrySet()) {
                int idx = lineEntry.getKey();
                if (idx < 0 || idx >= lines.size()) {
                    continue;
                }
                Line line = lines.get(idx);
                if (line == null) {
                    continue;
                }
                try {
                    line.setText(lineEntry.getValue() == null ? "" : lineEntry.getValue());
                } catch (Throwable t) {
                    logger.log(Level.FINE, "scoreboard restore line failed", t);
                }
            }
        }
        savedScoreboardLines.clear();
    }

    /** Drop per-player state on quit (injects are board-level; Paper bar cleaned on next scan). */
    public void onPlayerQuit(java.util.UUID uuid) {
        if (uuid == null || paperBossBar == null) {
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            try {
                paperBossBar.removePlayer(p);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** Test/diag: whether TAB API was reachable at init. */
    public boolean isAvailable() {
        return available;
    }

    public boolean hasBossBar() {
        return bossBackend != BossBackend.NONE;
    }

    /** {@code TAB}, {@code PAPER}, or {@code NONE} — for diagnostics / tests. */
    public String bossBackendName() {
        return bossBackend.name();
    }

    /** True when scoreboard inject is possible (manager present + lines configured). */
    public boolean hasScoreboard() {
        if (!available) {
            return false;
        }
        try {
            TabAPI api = TabAPI.getInstance();
            return api != null
                    && api.getScoreboardManager() != null
                    && config.scoreboardLines() != null
                    && !config.scoreboardLines().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
