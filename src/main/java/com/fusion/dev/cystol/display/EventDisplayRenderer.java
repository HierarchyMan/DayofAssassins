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
import java.util.Map;
import java.util.Objects;

/**
 * Pure TAB display rendering: bossbar title/progress + scoreboard line injects at configured indices.
 * Used by {@link TabDisplayService}; unit-tested without a live TAB/Paper server.
 *
 * <p><strong>Scoreboard model:</strong> never build a replacement board. Resolve configured inject
 * templates with top-N / phase placeholders and write only those rows onto the existing TAB board.
 */
public final class EventDisplayRenderer {

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
        return renderBossBar(
                timeline, now, announceLeadSeconds,
                countdownTitleTemplate, liveTitleTemplate, noKillsTitleTemplate,
                topKillerName, topKills,
                false, 0L, null
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
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(now, "now");
        EventPhase phase = timeline.phaseAt(now);
        boolean grace = timeline.inGraceWindow(now, graceEnabled, graceSeconds);
        if (grace) {
            long secs = timeline.secondsUntilNextPhase(now);
            String countdown = TimeUtil.formatCountdown(secs);
            String template = graceTitleTemplate == null || graceTitleTemplate.isBlank()
                    ? countdownTitleTemplate
                    : graceTitleTemplate;
            String title = TextUtil.apply(template, Map.of("countdown", countdown));
            float progress = (float) timeline.graceFillProgress(now, graceSeconds);
            return new BossBarView(title, clamp01(progress), true, true);
        }
        if (phase == EventPhase.COUNTDOWN) {
            long secs = timeline.secondsUntilNextPhase(now);
            String countdown = TimeUtil.formatCountdown(secs);
            String title = TextUtil.apply(countdownTitleTemplate, Map.of("countdown", countdown));
            float progress = (float) timeline.phaseFillProgress(now, announceLeadSeconds);
            return new BossBarView(title, clamp01(progress), true, false);
        }
        String remaining = TimeUtil.formatCountdown(timeline.secondsUntilNextPhase(now));
        String untilEnd = TimeUtil.formatCountdown(timeline.secondsUntilEnd(now));
        String title;
        if (topKillerName != null && !topKillerName.isBlank()) {
            title = TextUtil.apply(liveTitleTemplate, Map.of(
                    "top_killer", topKillerName,
                    "top_kills", String.valueOf(topKills == null ? 0 : topKills),
                    "remaining", remaining,
                    "until_end", untilEnd
            ));
        } else {
            title = TextUtil.apply(noKillsTitleTemplate, Map.of(
                    "remaining", remaining,
                    "until_end", untilEnd
            ));
        }
        float progress = (float) timeline.phaseFillProgress(now, announceLeadSeconds);
        return new BossBarView(title, clamp01(progress), false, false);
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
     *   <li>{@code %phase%} — localized phase label</li>
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
        String emptyN = emptyName == null || emptyName.isBlank() ? "—" : emptyName;
        String emptyK = emptyKills == null ? "0" : emptyKills;
        int slots = Math.max(1, topSlots);
        List<TopSlot> list = tops == null ? List.of() : tops;

        Map<String, String> ph = new HashMap<>();
        ph.put("phase", phaseLabel == null || phaseLabel.isBlank()
                ? (phase == null ? "IDLE" : phase.name())
                : phaseLabel);
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
     * Does <strong>not</strong> pad filler rows — non-configured indices are left untouched.
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
            String text = colorAmpersandToSection(TextUtil.apply(line.text(), ph));
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
        List<TopSlot> tops = topSlots(ranking, topSlots);
        Map<String, String> ph = buildPlaceholders(
                phase, phaseLabel, remainingUntilPhase, remainingUntilEnd,
                tops, topSlots, emptyName, emptyKills
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
            lines.set(line.line(), colorAmpersandToSection(line.text() == null ? " " : line.text()));
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
