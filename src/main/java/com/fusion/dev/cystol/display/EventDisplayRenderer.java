package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventTimeline;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.util.TextUtil;
import com.fusion.dev.cystol.util.TimeUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure display rendering: bossbar title/progress + scoreboard line injects.
 * Used by {@link TabDisplayService}; unit-tested without a live TAB/Paper server.
 *
 * <h2>Phase → HUD mental model (source of truth)</h2>
 *
 * <pre>
 * Stage          | %phase% label   | %timer_label%   | %remaining% means
 * ---------------+-----------------+-----------------+------------------
 * COUNTDOWN      | Starting soon   | Starts in       | until hunt start
 * COUNTDOWN+grace| Grace           | Hunt opens in   | until hunt start
 * HUNT           | Hunt            | Finale in       | until FFA moment
 * FFA (Finale)   | Finale          | Ends in         | until event end
 * PAUSED/IDLE/END| (HUD cleared)   | —               | —
 * </pre>
 *
 * <p><strong>Rules:</strong>
 * <ul>
 *   <li>Timers always name the <em>destination</em>, never the current stage
 *       (so “Hunt · Finale in 5m” cannot be misread as “hunt phase in 5m”).</li>
 *   <li>Hunt and Finale use separate bossbar templates — never cross-fallback
 *       (FFA must not reuse a hunt title that still says “Finale in”).</li>
 *   <li>{@code %timer_label%} is the single source of truth for that prefix;
 *       bossbar lang templates should include it rather than hard-coding English.</li>
 *   <li>Scoreboard: inject only configured rows (board grows / originals shift); never replace the whole board.</li>
 * </ul>
 */
public final class EventDisplayRenderer {

    /** Safe hard defaults if lang keys are blank (still use {@code %timer_label%}). */
    public static final String DEFAULT_HUNT_TITLE =
            "&cDay of Assassins &7| &f%top_killer% &8(%top_kills% kills) &7| %timer_label% &f%remaining%";
    public static final String DEFAULT_HUNT_TITLE_NO_KILLS =
            "&cDay of Assassins &7| &8No leader yet &7| %timer_label% &f%remaining%";
    public static final String DEFAULT_FFA_TITLE =
            "&cFinale &7| &f%top_killer% &8(%top_kills% kills) &7| %timer_label% &f%remaining%";
    public static final String DEFAULT_FFA_TITLE_NO_KILLS =
            "&cFinale &7| &8No leader yet &7| %timer_label% &f%remaining%";
    public static final String DEFAULT_COUNTDOWN_TITLE =
            "&cDay of Assassins &7starts in &f%countdown%";
    public static final String DEFAULT_GRACE_TITLE =
            "&aGrace &7| %timer_label% &f%countdown%";

    /**
     * Legacy scoreboard used bare {@code next %remaining%}, which reads as a current-stage
     * countdown. Rewrite only that token when {@code %timer_label%} is absent.
     */
    private static final Pattern LEGACY_NEXT_REMAINING = Pattern.compile(
            "(?i)\\bnext(\\s+(?:&[0-9a-fk-orx])*)?(%remaining%)"
    );

    /**
     * @param countdownMode true for pre-start countdown (including cosmetic grace)
     * @param graceMode     true only during the cosmetic last-N-seconds grace window
     */
    public record BossBarView(
            String titleLegacyAmpersand,
            float progress,
            boolean countdownMode,
            boolean graceMode
    ) {
    }

    public record ScoreboardView(List<String> linesSectionColor) {
        public String lineAt(int index) {
            if (index < 0 || index >= linesSectionColor.size()) {
                return null;
            }
            return linesSectionColor.get(index);
        }

        public int size() {
            return linesSectionColor.size();
        }
    }

    /** One leaderboard slot for placeholder fill (rank index 1..N in display order). */
    public record TopSlot(String name, int kills, int place) {
        public static TopSlot empty() {
            return new TopSlot(null, 0, 0);
        }

        public boolean isEmpty() {
            return name == null || name.isBlank();
        }
    }

    /**
     * Bundled timer prefixes from lang ({@code timer.*}).
     */
    public record TimerLabels(
            String startsIn,
            String huntOpensIn,
            String finaleIn,
            String endsIn
    ) {
        public static TimerLabels defaults() {
            return new TimerLabels("Starts in", "Hunt opens in", "Finale in", "Ends in");
        }

        public TimerLabels {
            startsIn = firstNonBlank(startsIn, "Starts in");
            huntOpensIn = firstNonBlank(huntOpensIn, "Hunt opens in");
            finaleIn = firstNonBlank(finaleIn, "Finale in");
            endsIn = firstNonBlank(endsIn, "Ends in");
        }
    }

    /**
     * Bundled friendly phase names from lang ({@code phase.*}).
     */
    public record PhaseLabels(
            String idle,
            String paused,
            String countdown,
            String grace,
            String hunt,
            String ffa,
            String ended
    ) {
        public static PhaseLabels defaults() {
            return new PhaseLabels(
                    "Idle", "Paused", "Starting soon", "Grace", "Hunt", "Finale", "Ended"
            );
        }

        public PhaseLabels {
            idle = firstNonBlank(idle, "Idle");
            paused = firstNonBlank(paused, "Paused");
            countdown = firstNonBlank(countdown, "Starting soon");
            grace = firstNonBlank(grace, "Grace");
            hunt = firstNonBlank(hunt, "Hunt");
            ffa = firstNonBlank(ffa, "Finale");
            ended = firstNonBlank(ended, "Ended");
        }
    }

    private EventDisplayRenderer() {
    }

    /**
     * Build bossbar content for the given timeline moment.
     *
     * <p>{@code %remaining%} is time until the <em>next phase change</em> (not total event end).
     * {@code %until_end%} is total time until event end when needed in lang templates.
     * Live bar progress fills the current phase segment.
     *
     * <p>When cosmetic grace is active (last N seconds of COUNTDOWN), title/progress use the
     * grace window only — real phase stays {@link EventPhase#COUNTDOWN}.
     *
     * <p>Hunt and Finale use separate title templates so the timer names the destination
     * (“Finale in …” / “Ends in …”) instead of a bare “next …”.
     */
    public static BossBarView renderBossBar(
            EventTimeline timeline,
            Instant now,
            long announceLeadSeconds,
            String countdownTitleTemplate,
            String liveTitleTemplate,
            String noKillsTitleTemplate,
            String topKillerName,
            Integer topKills
    ) {
        double progress = timeline.bossBarProgress(now, null, null, null);
        return renderBossBar(
                timeline, now,
                countdownTitleTemplate,
                liveTitleTemplate, noKillsTitleTemplate,
                liveTitleTemplate, noKillsTitleTemplate,
                topKillerName, topKills,
                false, 0L, null,
                TimerLabels.defaults(),
                progress
        );
    }

    /**
     * @param graceEnabled        config toggle
     * @param graceSeconds        window length; {@code <= 0} off
     * @param graceTitleTemplate  lang template with {@code %countdown%}
     */
    public static BossBarView renderBossBar(
            EventTimeline timeline,
            Instant now,
            long announceLeadSeconds,
            String countdownTitleTemplate,
            String liveTitleTemplate,
            String noKillsTitleTemplate,
            String topKillerName,
            Integer topKills,
            boolean graceEnabled,
            long graceSeconds,
            String graceTitleTemplate
    ) {
        double progress = timeline.bossBarProgress(now, null, null, null);
        return renderBossBar(
                timeline, now,
                countdownTitleTemplate,
                liveTitleTemplate, noKillsTitleTemplate,
                liveTitleTemplate, noKillsTitleTemplate,
                topKillerName, topKills,
                graceEnabled, graceSeconds, graceTitleTemplate,
                TimerLabels.defaults(),
                progress
        );
    }

    /**
     * Phase-split templates with default timer labels (tests / simple callers).
     */
    public static BossBarView renderBossBar(
            EventTimeline timeline,
            Instant now,
            long announceLeadSeconds,
            String countdownTitleTemplate,
            String huntTitleTemplate,
            String huntNoKillsTitleTemplate,
            String ffaTitleTemplate,
            String ffaNoKillsTitleTemplate,
            String topKillerName,
            Integer topKills,
            boolean graceEnabled,
            long graceSeconds,
            String graceTitleTemplate
    ) {
        double progress = timeline.bossBarProgress(now, null, null, null);
        return renderBossBar(
                timeline, now,
                countdownTitleTemplate,
                huntTitleTemplate, huntNoKillsTitleTemplate,
                ffaTitleTemplate, ffaNoKillsTitleTemplate,
                topKillerName, topKills,
                graceEnabled, graceSeconds, graceTitleTemplate,
                TimerLabels.defaults(),
                progress
        );
    }

    /**
     * Phase-split templates + timer labels; progress from schedule fallbacks (null anchors).
     */
    public static BossBarView renderBossBar(
            EventTimeline timeline,
            Instant now,
            long announceLeadSeconds,
            String countdownTitleTemplate,
            String huntTitleTemplate,
            String huntNoKillsTitleTemplate,
            String ffaTitleTemplate,
            String ffaNoKillsTitleTemplate,
            String topKillerName,
            Integer topKills,
            boolean graceEnabled,
            long graceSeconds,
            String graceTitleTemplate,
            TimerLabels timerLabels
    ) {
        double progress = timeline.bossBarProgress(now, null, null, null);
        return renderBossBar(
                timeline, now,
                countdownTitleTemplate,
                huntTitleTemplate, huntNoKillsTitleTemplate,
                ffaTitleTemplate, ffaNoKillsTitleTemplate,
                topKillerName, topKills,
                graceEnabled, graceSeconds, graceTitleTemplate,
                timerLabels,
                progress
        );
    }

    /**
     * Full bossbar render with phase-split templates, destination timer labels, and explicit bar progress.
     *
     * @param barProgress 0–1 from {@link EventTimeline#bossBarProgress} (fill pre-hunt, drain hunt/ffa)
     */
    public static BossBarView renderBossBar(
            EventTimeline timeline,
            Instant now,
            String countdownTitleTemplate,
            String huntTitleTemplate,
            String huntNoKillsTitleTemplate,
            String ffaTitleTemplate,
            String ffaNoKillsTitleTemplate,
            String topKillerName,
            Integer topKills,
            boolean graceEnabled,
            long graceSeconds,
            String graceTitleTemplate,
            TimerLabels timerLabels,
            double barProgress
    ) {
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(now, "now");
        TimerLabels timers = timerLabels == null ? TimerLabels.defaults() : timerLabels;
        EventPhase phase = timeline.phaseAt(now);
        boolean grace = timeline.inGraceWindow(now, graceEnabled, graceSeconds);
        String timerLabel = timerLabelForPhase(phase, grace, timers);
        float progress = clamp01((float) barProgress);

        if (grace) {
            long secs = timeline.secondsUntilNextPhase(now);
            String countdown = TimeUtil.formatCountdown(secs);
            String template = firstNonBlank(graceTitleTemplate, DEFAULT_GRACE_TITLE);
            if (template.isBlank()) {
                template = firstNonBlank(countdownTitleTemplate, DEFAULT_COUNTDOWN_TITLE);
            }
            String title = TextUtil.apply(template, Map.of(
                    "countdown", countdown,
                    "remaining", countdown,
                    "timer_label", timerLabel
            ));
            return new BossBarView(title, progress, true, true);
        }
        if (phase == EventPhase.COUNTDOWN) {
            long secs = timeline.secondsUntilNextPhase(now);
            String countdown = TimeUtil.formatCountdown(secs);
            String template = firstNonBlank(countdownTitleTemplate, DEFAULT_COUNTDOWN_TITLE);
            String title = TextUtil.apply(template, Map.of(
                    "countdown", countdown,
                    "remaining", countdown,
                    "timer_label", timerLabel
            ));
            return new BossBarView(title, progress, true, false);
        }

        // Never cross-fallback hunt ↔ ffa templates (wrong destination wording).
        String liveTitleTemplate;
        String noKillsTitleTemplate;
        if (phase == EventPhase.FFA) {
            liveTitleTemplate = firstNonBlank(ffaTitleTemplate, DEFAULT_FFA_TITLE);
            noKillsTitleTemplate = firstNonBlank(ffaNoKillsTitleTemplate, DEFAULT_FFA_TITLE_NO_KILLS);
        } else {
            liveTitleTemplate = firstNonBlank(huntTitleTemplate, DEFAULT_HUNT_TITLE);
            noKillsTitleTemplate = firstNonBlank(huntNoKillsTitleTemplate, DEFAULT_HUNT_TITLE_NO_KILLS);
        }

        String remaining = TimeUtil.formatCountdown(timeline.secondsUntilNextPhase(now));
        String untilEnd = TimeUtil.formatCountdown(timeline.secondsUntilEnd(now));
        String title;
        if (topKillerName != null && !topKillerName.isBlank()) {
            title = TextUtil.apply(liveTitleTemplate, Map.of(
                    "top_killer", topKillerName,
                    "top_kills", String.valueOf(topKills == null ? 0 : topKills),
                    "remaining", remaining,
                    "until_end", untilEnd,
                    "timer_label", timerLabel
            ));
        } else {
            title = TextUtil.apply(noKillsTitleTemplate, Map.of(
                    "remaining", remaining,
                    "until_end", untilEnd,
                    "timer_label", timerLabel
            ));
        }
        return new BossBarView(title, progress, false, false);
    }

    /**
     * Destination-facing timer prefix for HUD copy (pair with {@code %remaining%}).
     * Names what is coming — never the current stage — so "Hunt · Finale in 5m"
     * cannot be misread as "hunt phase in 5m".
     */
    public static String timerLabelForPhase(
            EventPhase phase,
            boolean graceMode,
            String startsIn,
            String huntOpensIn,
            String finaleIn,
            String endsIn
    ) {
        return timerLabelForPhase(phase, graceMode, new TimerLabels(startsIn, huntOpensIn, finaleIn, endsIn));
    }

    public static String timerLabelForPhase(EventPhase phase, boolean graceMode, TimerLabels labels) {
        TimerLabels t = labels == null ? TimerLabels.defaults() : labels;
        if (graceMode) {
            return t.huntOpensIn();
        }
        if (phase == null) {
            return t.startsIn();
        }
        return switch (phase) {
            case COUNTDOWN -> t.startsIn();
            case HUNT -> t.finaleIn();
            case FFA -> t.endsIn();
            case ENDED, IDLE, PAUSED -> t.endsIn();
        };
    }

    /**
     * Player-facing current-stage label (not the destination timer).
     * Grace is cosmetic and overrides COUNTDOWN display only.
     */
    public static String phaseLabel(EventPhase phase, boolean graceMode, PhaseLabels labels) {
        PhaseLabels p = labels == null ? PhaseLabels.defaults() : labels;
        if (graceMode) {
            return p.grace();
        }
        if (phase == null) {
            return p.idle();
        }
        return switch (phase) {
            case IDLE -> p.idle();
            case PAUSED -> p.paused();
            case COUNTDOWN -> p.countdown();
            case HUNT -> p.hunt();
            case FFA -> p.ffa();
            case ENDED -> p.ended();
        };
    }

    /**
     * Staff-facing stage label: friendly name, with enum in parentheses when they differ
     * (e.g. {@code Finale (FFA)}) so ops still match {@code /preciv admin phase} tokens.
     */
    public static String opsPhaseLabel(EventPhase phase, PhaseLabels labels) {
        if (phase == null) {
            return phaseLabel(null, false, labels);
        }
        String friendly = phaseLabel(phase, false, labels);
        if (friendly.equalsIgnoreCase(phase.name())) {
            return friendly;
        }
        return friendly + " (" + phase.name() + ")";
    }

    /**
     * Upgrade legacy scoreboard templates that used bare {@code next %remaining%}.
     * Idempotent when {@code %timer_label%} is already present.
     */
    public static String normalizeScoreboardTemplate(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        if (text.toLowerCase(Locale.ROOT).contains("%timer_label%")) {
            return text;
        }
        return LEGACY_NEXT_REMAINING.matcher(text).replaceAll("%timer_label%$1$2");
    }

    /**
     * Map dense-ranking prefix into fixed top-N slots (missing ranks stay empty).
     *
     * @param ranking full sorted leaderboard
     * @param slots   desired slot count (e.g. 3)
     */
    public static List<TopSlot> topSlots(List<DenseRanking.Entry> ranking, int slots) {
        int n = Math.max(0, slots);
        List<TopSlot> out = new ArrayList<>(n);
        int available = ranking == null ? 0 : ranking.size();
        for (int i = 0; i < n; i++) {
            if (i < available) {
                DenseRanking.Entry e = ranking.get(i);
                out.add(new TopSlot(e.name(), e.kills(), e.place()));
            } else {
                out.add(TopSlot.empty());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Placeholder bag for inject templates and bossbar.
     *
     * <ul>
     *   <li>{@code %top1_name%} {@code %top1_kills%} {@code %top1_place%} … through top-slots</li>
     *   <li>{@code %top_killer%} / {@code %top_kills%} — aliases for slot 1 (compat)</li>
     *   <li>{@code %phase%} — localized <em>current</em> stage label</li>
     *   <li>{@code %timer_label%} — destination prefix (“Finale in”, “Ends in”, …)</li>
     *   <li>{@code %remaining%} — until next phase boundary</li>
     *   <li>{@code %until_end%} — until event end</li>
     * </ul>
     */
    public static Map<String, String> buildPlaceholders(
            EventPhase phase,
            String phaseLabel,
            String remainingUntilPhase,
            String remainingUntilEnd,
            List<TopSlot> tops,
            int topSlots,
            String emptyName,
            String emptyKills
    ) {
        return buildPlaceholders(
                phase, phaseLabel, remainingUntilPhase, remainingUntilEnd,
                null, tops, topSlots, emptyName, emptyKills
        );
    }

    /**
     * @param timerLabel destination-facing prefix for {@code %timer_label%} (may be null/blank)
     */
    public static Map<String, String> buildPlaceholders(
            EventPhase phase,
            String phaseLabel,
            String remainingUntilPhase,
            String remainingUntilEnd,
            String timerLabel,
            List<TopSlot> tops,
            int topSlots,
            String emptyName,
            String emptyKills
    ) {
        String emptyN = emptyName == null || emptyName.isBlank() ? "—" : emptyName;
        String emptyK = emptyKills == null ? "0" : emptyKills;
        int slots = Math.max(1, topSlots);
        List<TopSlot> list = tops == null ? List.of() : tops;

        Map<String, String> ph = new HashMap<>();
        ph.put("phase", phaseLabel == null || phaseLabel.isBlank()
                ? (phase == null ? "IDLE" : phase.name())
                : phaseLabel);
        ph.put("timer_label", timerLabel == null ? "" : timerLabel);
        ph.put("remaining", remainingUntilPhase == null ? "" : remainingUntilPhase);
        ph.put("until_end", remainingUntilEnd == null ? "" : remainingUntilEnd);

        for (int i = 1; i <= slots; i++) {
            TopSlot slot = i - 1 < list.size() ? list.get(i - 1) : TopSlot.empty();
            boolean empty = slot == null || slot.isEmpty();
            ph.put("top" + i + "_name", empty ? emptyN : slot.name());
            ph.put("top" + i + "_kills", empty ? emptyK : String.valueOf(slot.kills()));
            ph.put("top" + i + "_place", empty ? String.valueOf(i) : String.valueOf(slot.place()));
        }

        // Compat aliases for older templates / bossbar
        TopSlot first = list.isEmpty() ? TopSlot.empty() : list.getFirst();
        boolean firstEmpty = first == null || first.isEmpty();
        ph.put("top_killer", firstEmpty ? emptyN : first.name());
        ph.put("top_kills", firstEmpty ? emptyK : String.valueOf(first.kills()));
        return ph;
    }

    /**
     * Resolve only the configured event lines (index → text) for injection into an existing TAB board.
     * Live display inserts/grows those rows on the board; non-configured indices are left untouched.
     * Applies {@link #normalizeScoreboardTemplate(String)} so legacy “next %remaining%” lines upgrade.
     */
    public static Map<Integer, String> renderScoreboardInjections(
            List<PluginConfig.ScoreboardLine> configuredLines,
            Map<String, String> placeholders
    ) {
        Map<Integer, String> injected = new LinkedHashMap<>();
        if (configuredLines == null || configuredLines.isEmpty()) {
            return Map.copyOf(injected);
        }
        Map<String, String> ph = placeholders == null ? Map.of() : placeholders;
        for (PluginConfig.ScoreboardLine line : configuredLines) {
            if (line == null || line.text() == null || line.line() < 0) {
                continue;
            }
            String template = normalizeScoreboardTemplate(line.text());
            String text = colorAmpersandToSection(TextUtil.apply(template, ph));
            injected.put(line.line(), text.isBlank() ? " " : text);
        }
        return Map.copyOf(injected);
    }

    /**
     * Convenience: build placeholders from ranking + render injects.
     */
    public static Map<Integer, String> renderScoreboardInjections(
            List<PluginConfig.ScoreboardLine> configuredLines,
            EventPhase phase,
            String phaseLabel,
            String remainingUntilPhase,
            String remainingUntilEnd,
            List<DenseRanking.Entry> ranking,
            int topSlots,
            String emptyName,
            String emptyKills
    ) {
        return renderScoreboardInjections(
                configuredLines, phase, phaseLabel, remainingUntilPhase, remainingUntilEnd,
                null, ranking, topSlots, emptyName, emptyKills
        );
    }

    /**
     * @param timerLabel destination-facing {@code %timer_label%} (e.g. "Finale in")
     */
    public static Map<Integer, String> renderScoreboardInjections(
            List<PluginConfig.ScoreboardLine> configuredLines,
            EventPhase phase,
            String phaseLabel,
            String remainingUntilPhase,
            String remainingUntilEnd,
            String timerLabel,
            List<DenseRanking.Entry> ranking,
            int topSlots,
            String emptyName,
            String emptyKills
    ) {
        List<TopSlot> tops = topSlots(ranking, topSlots);
        Map<String, String> ph = buildPlaceholders(
                phase, phaseLabel, remainingUntilPhase, remainingUntilEnd,
                timerLabel, tops, topSlots, emptyName, emptyKills
        );
        return renderScoreboardInjections(configuredLines, ph);
    }

    /**
     * @deprecated prefer {@link #renderScoreboardInjections(List, Map)} with full top-N placeholders.
     */
    @Deprecated
    public static Map<Integer, String> renderScoreboardInjections(
            List<PluginConfig.ScoreboardLine> configuredLines,
            EventPhase phase,
            String phaseLabel,
            String remainingFormatted,
            String topKillerName,
            int topKills
    ) {
        List<TopSlot> tops = List.of(
                topKillerName == null || topKillerName.isBlank()
                        ? TopSlot.empty()
                        : new TopSlot(topKillerName, topKills, 1)
        );
        Map<String, String> ph = buildPlaceholders(
                phase, phaseLabel, remainingFormatted, remainingFormatted,
                tops, Math.max(3, tops.size()), "—", "0"
        );
        return renderScoreboardInjections(configuredLines, ph);
    }

    /**
     * Build a padded line list from configured injects (index 0..max inclusive).
     * Prefer {@link #renderScoreboardInjections} for live TAB updates.
     */
    public static ScoreboardView renderScoreboardLines(
            List<PluginConfig.ScoreboardLine> configuredLines,
            EventPhase phase,
            String phaseLabel,
            String remainingFormatted,
            String topKillerName,
            int topKills
    ) {
        Map<Integer, String> injected = renderScoreboardInjections(
                configuredLines, phase, phaseLabel, remainingFormatted, topKillerName, topKills
        );
        if (injected.isEmpty()) {
            return new ScoreboardView(List.of());
        }
        int maxIdx = -1;
        for (int idx : injected.keySet()) {
            maxIdx = Math.max(maxIdx, idx);
        }
        List<String> rendered = new ArrayList<>(maxIdx + 1);
        for (int i = 0; i <= maxIdx; i++) {
            rendered.add(" ");
        }
        for (Map.Entry<Integer, String> e : injected.entrySet()) {
            rendered.set(e.getKey(), e.getValue());
        }
        return new ScoreboardView(List.copyOf(rendered));
    }

    /**
     * @deprecated use the phase-label overload
     */
    @Deprecated
    public static ScoreboardView renderScoreboardLines(
            List<PluginConfig.ScoreboardLine> configuredLines,
            EventPhase phase,
            String topKillerName,
            int topKills
    ) {
        return renderScoreboardLines(
                configuredLines,
                phase,
                phase == null ? "IDLE" : phase.name(),
                "",
                topKillerName,
                topKills
        );
    }

    /**
     * Build initial template lines (indices padded). Diagnostics only —
     * live display injects into the existing TAB board.
     */
    public static List<String> buildTemplateLines(List<PluginConfig.ScoreboardLine> configuredLines) {
        List<String> lines = new ArrayList<>();
        if (configuredLines == null) {
            return lines;
        }
        for (PluginConfig.ScoreboardLine line : configuredLines) {
            if (line == null) {
                continue;
            }
            while (lines.size() <= line.line()) {
                lines.add(" ");
            }
            String raw = line.text() == null ? " " : normalizeScoreboardTemplate(line.text());
            lines.set(line.line(), colorAmpersandToSection(raw));
        }
        return lines;
    }

    /** Progress for TAB bossbar API: 0–100 (not 0–1). */
    public static float progressPercent(float progress01) {
        return clamp01(progress01) * 100f;
    }

    public static String colorAmpersandToSection(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('&', '§');
    }

    static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    private static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }
}
