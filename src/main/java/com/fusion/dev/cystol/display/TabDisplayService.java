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
import java.util.Collections;
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
 * normal TAB layout. Default: contiguous inject rows are <strong>inserted</strong> (board grows,
 * original lines shift down). Config {@code tab.scoreboard.replace-existing: true} keeps the
 * legacy overwrite path (setText only, no grow). {@link #clear()} undoes either mode.
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
    /** Last applied bossbar color name (e.g. RED) — reapplied when phase changes. */
    private String lastBossColor;
    /** Last applied bossbar style token (PROGRESS / NOTCHED_*). */
    private String lastBossStyle;
    private String lastScoreboardFingerprint;
    /** Throttle "bossbar missing" log — never spam once per second. */
    private long lastBossMissingLogMs;
    /** Online player count last time we scanned for bossbar viewers (join/leave delta). */
    private int lastBossViewerScanSize = -1;

    /**
     * Per-board inject bookkeeping: inserted/appended line counts + any overwritten cell text.
     * Keyed by scoreboard name (or {@code active:<uuid>} when unnamed).
     */
    private final Map<String, ScoreboardInjectState> injectStateByBoard = new ConcurrentHashMap<>();

    /**
     * Undo data for one TAB scoreboard we expanded or patched.
     *
     * @param insertedCount lines inserted as a block (removed from {@code insertAt} on clear)
     * @param insertAt      0-based start of the inserted block; ignored when {@code insertedCount == 0}
     * @param appendedCount blank lines appended at the end for grow-to-fit; removed from the end on clear
     * @param overwritten   original text for cells we overwrote without insert (sparse absolute indices)
     */
    private record ScoreboardInjectState(
            int insertedCount,
            int insertAt,
            int appendedCount,
            Map<Integer, String> overwritten
    ) {
        static ScoreboardInjectState inserted(int count, int at) {
            return new ScoreboardInjectState(count, at, 0, Map.of());
        }

        static ScoreboardInjectState grown(int appended, Map<Integer, String> overwritten) {
            Map<Integer, String> copy = overwritten == null || overwritten.isEmpty()
                    ? Map.of()
                    : Map.copyOf(overwritten);
            return new ScoreboardInjectState(0, -1, Math.max(0, appended), copy);
        }

        /**
         * Legacy replace mode: keep a live map so newly touched indices still snapshot
         * originals (same as the old ConcurrentHashMap-per-board bookkeeping).
         */
        static ScoreboardInjectState replaceExisting(Map<Integer, String> liveOriginals) {
            Map<Integer, String> map = liveOriginals == null ? new ConcurrentHashMap<>() : liveOriginals;
            return new ScoreboardInjectState(0, -1, 0, map);
        }
    }

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

            boolean graceActive = timeline.inGraceWindow(
                    now, config.graceEnabled(), config.graceSeconds()
            );
            EventDisplayRenderer.TimerLabels timerLabels = loadTimerLabels();
            // Hunt may fall back to legacy bossbar.title; Finale must NOT (legacy often says
            // "Finale in" which is wrong once already in FFA).
            String huntTitle = firstNonBlank(
                    lang.raw("bossbar.hunt-title", null),
                    lang.raw("bossbar.title", null)
            );
            String huntNoKills = firstNonBlank(
                    lang.raw("bossbar.hunt-title-no-kills", null),
                    lang.raw("bossbar.title-no-kills", null)
            );
            String ffaTitle = lang.raw("bossbar.ffa-title", null);
            String ffaNoKills = lang.raw("bossbar.ffa-title-no-kills", null);
            double barProgress = eventManager.bossBarProgress(now);
            EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                    timeline,
                    now,
                    lang.raw("bossbar.countdown-title", null),
                    huntTitle,
                    huntNoKills,
                    ffaTitle,
                    ffaNoKills,
                    topName,
                    topKills,
                    config.graceEnabled(),
                    config.graceSeconds(),
                    lang.raw("bossbar.grace-title", null),
                    timerLabels,
                    barProgress
            );

            active = true;
            updateBossBar(bar, phase, graceActive);
            if (available) {
                TabAPI api = TabAPI.getInstance();
                if (api != null) {
                    updateScoreboardInject(api, phase, graceActive, timeline, now, topRanking, topSlots);
                }
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "display update failed", t);
        }
    }

    private void updateBossBar(EventDisplayRenderer.BossBarView bar, EventPhase phase, boolean graceActive) {
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
            // Prefer view.graceMode when present; graceActive keeps callers in sync
            boolean grace = graceActive || bar.graceMode();
            String colorName = config.tabBossbarColorForDisplay(phase, grace);
            String styleName = config.tabBossbarStyle();
            if (bossBackend == BossBackend.TAB) {
                updateTabBossBar(title, bar.progress(), colorName, styleName);
            } else {
                updatePaperBossBar(title, bar.progress(), colorName, styleName);
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "bossbar update failed — recreating", t);
            destroyBossBars();
            lastBossTitle = null;
            lastBossProgress = Float.NaN;
            lastBossColor = null;
            lastBossStyle = null;
            lastBossViewerScanSize = -1;
        }
    }

    private void updateTabBossBar(String titleSection, float progress01, String colorName, String styleName) {
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
        if (colorName != null && !colorName.equals(lastBossColor)) {
            try {
                tabBossBar.setColor(parseTabBarColor(colorName));
                lastBossColor = colorName;
            } catch (Throwable t) {
                logger.log(Level.FINE, "TAB bossbar setColor failed for " + colorName, t);
            }
        }
        if (styleName != null && !styleName.equals(lastBossStyle)) {
            try {
                tabBossBar.setStyle(parseTabBarStyle(styleName));
                lastBossStyle = styleName;
            } catch (Throwable t) {
                logger.log(Level.FINE, "TAB bossbar setStyle failed for " + styleName, t);
            }
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

    private void updatePaperBossBar(String titleSection, float progress01, String colorName, String styleName) {
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
        if (colorName != null && !colorName.equals(lastBossColor)) {
            try {
                paperBossBar.setColor(parsePaperBarColor(colorName));
                lastBossColor = colorName;
            } catch (Throwable t) {
                logger.log(Level.FINE, "Paper bossbar setColor failed for " + colorName, t);
            }
        }
        if (styleName != null && !styleName.equals(lastBossStyle)) {
            try {
                paperBossBar.setStyle(parsePaperBarStyle(styleName));
                lastBossStyle = styleName;
            } catch (Throwable t) {
                logger.log(Level.FINE, "Paper bossbar setStyle failed for " + styleName, t);
            }
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
            boolean graceActive,
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
        EventDisplayRenderer.PhaseLabels phaseLabels = loadPhaseLabels();
        EventDisplayRenderer.TimerLabels timerLabels = loadTimerLabels();
        // Cosmetic grace label does not change real EventPhase
        String phaseLabel = EventDisplayRenderer.phaseLabel(phase, graceActive, phaseLabels);
        String timerLabel = EventDisplayRenderer.timerLabelForPhase(phase, graceActive, timerLabels);
        Map<Integer, String> injections = EventDisplayRenderer.renderScoreboardInjections(
                configured,
                phase,
                phaseLabel,
                remainingPhase,
                remainingEnd,
                timerLabel,
                topRanking,
                topSlots,
                config.scoreboardEmptyName(),
                config.scoreboardEmptyKills()
        );
        if (injections.isEmpty()) {
            return;
        }

        String fingerprint = scoreboardFingerprint(phase, graceActive, topRanking, injections);
        boolean contentChanged = !fingerprint.equals(lastScoreboardFingerprint);

        // Fast path: text unchanged — only inject boards we have never touched (new join / board switch).
        if (!contentChanged && !injectStateByBoard.isEmpty()) {
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
                    if (!injectStateByBoard.containsKey(key)) {
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
            boolean firstTouch = !injectStateByBoard.containsKey(e.getKey());
            if (contentChanged || firstTouch) {
                injectLines(e.getKey(), e.getValue(), injections);
            }
        }
        lastScoreboardFingerprint = fingerprint;
    }

    /**
     * Write event lines into {@code board} at configured indices.
     *
     * <p>Default (insert): on first touch, contiguous inject block is <strong>inserted</strong>
     * so total length grows and existing rows shift down. Sparse absolute indices grow-to-fit.
     *
     * <p>Legacy ({@code tab.scoreboard.replace-existing: true}): overwrite existing rows only —
     * no {@code addLine}, out-of-range indices skipped, original text restored on clear.
     */
    private void injectLines(
            String boardKey,
            Scoreboard board,
            Map<Integer, String> injections
    ) {
        if (board == null || injections.isEmpty()) {
            return;
        }
        if (config.scoreboardReplaceExisting()) {
            injectLinesReplaceExisting(boardKey, board, injections);
            return;
        }
        injectLinesInsert(boardKey, board, injections);
    }

    /**
     * Legacy replace path (exact old behaviour): {@link Line#setText} on in-range indices only.
     * Does not grow the board. Snapshots original cell text once for {@link #clear()}.
     */
    private void injectLinesReplaceExisting(
            String boardKey,
            Scoreboard board,
            Map<Integer, String> injections
    ) {
        List<Line> lines;
        try {
            lines = board.getLines();
        } catch (Throwable t) {
            logger.log(Level.FINE, "scoreboard getLines failed for " + boardKey, t);
            return;
        }
        // Old path: empty/missing board → no inject (employer boards already have filler rows).
        if (lines == null || lines.isEmpty()) {
            return;
        }

        // Live originals map (same as old savedScoreboardLines.computeIfAbsent).
        ScoreboardInjectState existing = injectStateByBoard.get(boardKey);
        final Map<Integer, String> originals;
        if (existing != null && existing.overwritten() instanceof ConcurrentHashMap) {
            originals = existing.overwritten();
        } else {
            originals = new ConcurrentHashMap<>();
            if (existing != null && !existing.overwritten().isEmpty()) {
                originals.putAll(existing.overwritten());
            }
            injectStateByBoard.put(boardKey, ScoreboardInjectState.replaceExisting(originals));
        }

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

    /**
     * Insert/grow path: expand board on first touch, then refresh inject cell text.
     */
    private void injectLinesInsert(
            String boardKey,
            Scoreboard board,
            Map<Integer, String> injections
    ) {
        List<Line> lines;
        try {
            lines = board.getLines();
        } catch (Throwable t) {
            logger.log(Level.FINE, "scoreboard getLines failed for " + boardKey, t);
            return;
        }
        if (lines == null) {
            return;
        }

        // First touch: expand (insert or grow). Later ticks only refresh text.
        if (!injectStateByBoard.containsKey(boardKey)) {
            try {
                expandBoardForInject(boardKey, board, lines, injections);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "scoreboard expand failed for " + boardKey, t);
                return;
            }
            try {
                lines = board.getLines();
            } catch (Throwable t) {
                logger.log(Level.FINE, "scoreboard getLines after expand failed for " + boardKey, t);
                return;
            }
            if (lines == null) {
                return;
            }
        }

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

    /**
     * Expand {@code board} so inject indices exist without permanently eating normal rows.
     * Records undo data in {@link #injectStateByBoard}.
     */
    private void expandBoardForInject(
            String boardKey,
            Scoreboard board,
            List<Line> linesBefore,
            Map<Integer, String> injections
    ) {
        List<Integer> sorted = new ArrayList<>(injections.keySet());
        Collections.sort(sorted);
        if (sorted.isEmpty() || sorted.getFirst() < 0) {
            injectStateByBoard.put(boardKey, ScoreboardInjectState.grown(0, Map.of()));
            return;
        }
        int minIdx = sorted.getFirst();
        int maxIdx = sorted.getLast();
        int size = linesBefore == null ? 0 : linesBefore.size();
        boolean contiguous = true;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i) != sorted.get(i - 1) + 1) {
                contiguous = false;
                break;
            }
        }

        // Contiguous block at or inside current board → insert so originals shift down.
        // minIdx == size is pure append (shift loop is a no-op).
        if (contiguous && minIdx <= size) {
            int n = sorted.size();
            int start = minIdx;
            for (int i = 0; i < n; i++) {
                board.addLine(" ");
            }
            List<Line> lines;
            try {
                lines = board.getLines();
            } catch (Throwable t) {
                // Best-effort undo of partial adds
                for (int i = 0; i < n; i++) {
                    try {
                        List<Line> cur = board.getLines();
                        if (cur != null && !cur.isEmpty()) {
                            board.removeLine(cur.size() - 1);
                        }
                    } catch (Throwable ignored) {
                        break;
                    }
                }
                throw t;
            }
            if (lines == null || lines.size() < size + n) {
                logger.warning("scoreboard addLine did not grow board " + boardKey
                        + " (size was " + size + ", expected " + (size + n) + ")");
                injectStateByBoard.put(boardKey, ScoreboardInjectState.grown(0, Map.of()));
                return;
            }
            // Shift original [start .. size) down by n into the newly added tail slots.
            for (int i = size - 1; i >= start; i--) {
                try {
                    String text = lines.get(i).getText();
                    lines.get(i + n).setText(text == null ? "" : text);
                } catch (Throwable t) {
                    logger.log(Level.FINE, "scoreboard shift line " + i + " on " + boardKey + " failed", t);
                }
            }
            injectStateByBoard.put(boardKey, ScoreboardInjectState.inserted(n, start));
            logger.fine("scoreboard insert " + n + " line(s) at " + start + " on " + boardKey
                    + " (was " + size + " → " + (size + n) + ")");
            return;
        }

        // Sparse absolute indices, or block past a gap: grow to maxIdx, overwrite only inject cells.
        int appended = 0;
        int needSize = maxIdx + 1;
        while (true) {
            List<Line> cur;
            try {
                cur = board.getLines();
            } catch (Throwable t) {
                break;
            }
            int curSize = cur == null ? 0 : cur.size();
            if (curSize >= needSize) {
                break;
            }
            board.addLine(" ");
            appended++;
            if (appended > 64) {
                // Hard stop — something is wrong with TAB line list
                logger.warning("scoreboard grow aborted for " + boardKey + " after 64 addLine calls");
                break;
            }
        }
        Map<Integer, String> overwritten = new HashMap<>();
        List<Line> after;
        try {
            after = board.getLines();
        } catch (Throwable t) {
            injectStateByBoard.put(boardKey, ScoreboardInjectState.grown(appended, Map.of()));
            return;
        }
        if (after != null) {
            for (int idx : sorted) {
                // Only snapshot cells that existed before we grew (true overwrite victims).
                if (idx < 0 || idx >= size || idx >= after.size()) {
                    continue;
                }
                try {
                    String raw = after.get(idx).getText();
                    overwritten.put(idx, raw == null ? "" : raw);
                } catch (Throwable t) {
                    overwritten.put(idx, "");
                }
            }
        }
        injectStateByBoard.put(boardKey, ScoreboardInjectState.grown(appended, overwritten));
        if (appended > 0) {
            logger.fine("scoreboard append " + appended + " line(s) on " + boardKey
                    + " for max inject index " + maxIdx);
        }
    }

    private static String scoreboardFingerprint(
            EventPhase phase,
            boolean graceActive,
            List<DenseRanking.Entry> topRanking,
            Map<Integer, String> injections
    ) {
        StringBuilder b = new StringBuilder(96);
        b.append(phase == null ? "IDLE" : phase.name()).append(graceActive ? "|G|" : "|");
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
            String colorName = config.tabBossbarColor();
            String styleName = config.tabBossbarStyle();
            paperBossBar = Bukkit.createBossBar(
                    BOSSBAR_TITLE_SEED,
                    parsePaperBarColor(colorName),
                    parsePaperBarStyle(styleName)
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
            lastBossColor = colorName;
            lastBossStyle = styleName;
            logger.info("Created Day of Assassins Paper/Bukkit bossbar (TAB bossbar feature not required)");
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to create Paper bossbar", t);
            paperBossBar = null;
            bossBackend = BossBackend.NONE;
        }
    }

    private BossBar createEventBossBar(BossBarManager bbm) {
        // TAB 5.x: createBossBar(title, progress 0-100, color, style) — generates UUID name.
        // Progress seed 0; live updates use setProgress with 0–100.
        String colorName = config.tabBossbarColor();
        String styleName = config.tabBossbarStyle();
        BossBar bar = bbm.createBossBar(
                BOSSBAR_TITLE_SEED,
                0f,
                parseTabBarColor(colorName),
                parseTabBarStyle(styleName)
        );
        lastBossColor = colorName;
        lastBossStyle = styleName;
        return bar;
    }

    static BarColor parseTabBarColor(String name) {
        if (name == null || name.isBlank()) {
            return BarColor.RED;
        }
        try {
            return BarColor.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarColor.RED;
        }
    }

    static BarStyle parseTabBarStyle(String name) {
        if (name == null || name.isBlank()) {
            return BarStyle.PROGRESS;
        }
        String u = name.trim().toUpperCase(java.util.Locale.ROOT);
        if ("SOLID".equals(u)) {
            u = "PROGRESS";
        }
        if (u.startsWith("SEGMENTED_")) {
            u = "NOTCHED_" + u.substring("SEGMENTED_".length());
        }
        try {
            return BarStyle.valueOf(u);
        } catch (IllegalArgumentException e) {
            return BarStyle.PROGRESS;
        }
    }

    static org.bukkit.boss.BarColor parsePaperBarColor(String name) {
        if (name == null || name.isBlank()) {
            return org.bukkit.boss.BarColor.RED;
        }
        try {
            return org.bukkit.boss.BarColor.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return org.bukkit.boss.BarColor.RED;
        }
    }

    /**
     * Map config style tokens to Bukkit. Canonical config uses PROGRESS / NOTCHED_*;
     * Bukkit uses SOLID / SEGMENTED_*.
     */
    static org.bukkit.boss.BarStyle parsePaperBarStyle(String name) {
        if (name == null || name.isBlank()) {
            return org.bukkit.boss.BarStyle.SOLID;
        }
        String u = name.trim().toUpperCase(java.util.Locale.ROOT);
        if ("PROGRESS".equals(u) || "SOLID".equals(u)) {
            return org.bukkit.boss.BarStyle.SOLID;
        }
        if (u.startsWith("NOTCHED_")) {
            u = "SEGMENTED_" + u.substring("NOTCHED_".length());
        }
        try {
            return org.bukkit.boss.BarStyle.valueOf(u);
        } catch (IllegalArgumentException e) {
            return org.bukkit.boss.BarStyle.SOLID;
        }
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
        lastBossColor = null;
        lastBossStyle = null;
    }

    public void clear() {
        // Cheap no-op when already idle — tick must not pay restore/hide cost every second.
        if (!active && injectStateByBoard.isEmpty()) {
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
        lastBossColor = null;
        lastBossStyle = null;
        lastScoreboardFingerprint = null;
        lastBossViewerScanSize = -1;

        try {
            hideBossBarFromEveryone();
            if (available) {
                restoreScoreboardLines(TabAPI.getInstance());
            } else {
                injectStateByBoard.clear();
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "display clear failed", t);
            injectStateByBoard.clear();
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
        if (injectStateByBoard.isEmpty()) {
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
                        if (activeSb != null) {
                            String name = activeSb.getName();
                            byName.put(name == null ? ("active:" + p.getUniqueId()) : name, activeSb);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }

        for (Map.Entry<String, ScoreboardInjectState> boardEntry : injectStateByBoard.entrySet()) {
            String key = boardEntry.getKey();
            ScoreboardInjectState state = boardEntry.getValue();
            if (state == null) {
                continue;
            }
            Scoreboard board = byName.get(key);
            if (board == null) {
                continue;
            }
            try {
                // Inserted block: removing from insertAt N times shifts originals back up.
                if (state.insertedCount() > 0 && state.insertAt() >= 0) {
                    for (int i = 0; i < state.insertedCount(); i++) {
                        List<Line> lines = board.getLines();
                        if (lines == null || state.insertAt() >= lines.size()) {
                            break;
                        }
                        board.removeLine(state.insertAt());
                    }
                }
                // Grow-to-fit tail: drop the blanks we appended at the end.
                if (state.appendedCount() > 0) {
                    for (int i = 0; i < state.appendedCount(); i++) {
                        List<Line> lines = board.getLines();
                        if (lines == null || lines.isEmpty()) {
                            break;
                        }
                        board.removeLine(lines.size() - 1);
                    }
                }
                // Sparse overwrite restore (only when we did not insert a contiguous block).
                if (!state.overwritten().isEmpty()) {
                    List<Line> lines = board.getLines();
                    if (lines != null) {
                        for (Map.Entry<Integer, String> lineEntry : state.overwritten().entrySet()) {
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
                }
            } catch (Throwable t) {
                logger.log(Level.FINE, "scoreboard restore failed for " + key, t);
            }
        }
        injectStateByBoard.clear();
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

    private EventDisplayRenderer.TimerLabels loadTimerLabels() {
        return new EventDisplayRenderer.TimerLabels(
                lang.raw("timer.starts-in", "Starts in"),
                lang.raw("timer.hunt-opens-in", "Hunt opens in"),
                lang.raw("timer.finale-in", "Finale in"),
                lang.raw("timer.ends-in", "Ends in")
        );
    }

    private EventDisplayRenderer.PhaseLabels loadPhaseLabels() {
        return new EventDisplayRenderer.PhaseLabels(
                lang.raw("phase.idle", "Idle"),
                lang.raw("phase.paused", "Paused"),
                lang.raw("phase.countdown", "Starting soon"),
                lang.raw("phase.grace", "Grace"),
                lang.raw("phase.hunt", "Hunt"),
                lang.raw("phase.ffa", "Finale"),
                lang.raw("phase.ended", "Ended")
        );
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }
}
