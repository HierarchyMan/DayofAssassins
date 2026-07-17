package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventTimeline;
import com.fusion.dev.cystol.util.TextUtil;
import com.fusion.dev.cystol.util.TimeUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure TAB display rendering: bossbar title/progress + scoreboard lines at configured indices.
 * Used by {@link TabDisplayService}; unit-tested without a live TAB/Paper server.
 */
public final class EventDisplayRenderer {

    public record BossBarView(String titleLegacyAmpersand, float progress, boolean countdownMode) {
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

    private EventDisplayRenderer() {
    }

    /**
     * Build bossbar content for the given timeline moment.
     *
     * <p>Live templates may use {@code %top_killer%}, {@code %top_kills%}, {@code %remaining%}.
     *
     * @param countdownTitleTemplate lang bossbar.countdown-title
     * @param liveTitleTemplate      lang bossbar.title
     * @param noKillsTitleTemplate   lang bossbar.title-no-kills
     * @param announceLeadSeconds    config lead used for fill window
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
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(now, "now");
        EventPhase phase = timeline.phaseAt(now);
        if (phase == EventPhase.COUNTDOWN) {
            long secs = timeline.start()
                    .map(s -> Math.max(0, s.getEpochSecond() - now.getEpochSecond()))
                    .orElse(0L);
            String countdown = TimeUtil.formatCountdown(secs);
            String title = TextUtil.apply(countdownTitleTemplate, Map.of("countdown", countdown));
            float progress = (float) timeline.countdownFillProgress(now, announceLeadSeconds);
            return new BossBarView(title, clamp01(progress), true);
        }
        String remaining = TimeUtil.formatCountdown(timeline.secondsUntilEnd(now));
        String title;
        if (topKillerName != null && !topKillerName.isBlank()) {
            title = TextUtil.apply(liveTitleTemplate, Map.of(
                    "top_killer", topKillerName,
                    "top_kills", String.valueOf(topKills == null ? 0 : topKills),
                    "remaining", remaining
            ));
        } else {
            title = TextUtil.apply(noKillsTitleTemplate, Map.of("remaining", remaining));
        }
        float progress = (float) timeline.liveFillProgress(now);
        return new BossBarView(title, clamp01(progress), false);
    }

    /**
     * Inject event templates into a sparse line list at configured indices, resolve placeholders,
     * convert &amp; → section for TAB.
     *
     * <p>Placeholders: {@code %top_killer%}, {@code %top_kills%}, {@code %phase%} (display label),
     * {@code %remaining%}.
     */
    public static ScoreboardView renderScoreboardLines(
            List<PluginConfig.ScoreboardLine> configuredLines,
            EventPhase phase,
            String phaseLabel,
            String remainingFormatted,
            String topKillerName,
            int topKills
    ) {
        Map<String, String> ph = new HashMap<>();
        ph.put("top_killer", topKillerName == null || topKillerName.isBlank() ? "—" : topKillerName);
        ph.put("top_kills", String.valueOf(topKills));
        ph.put("phase", phaseLabel == null || phaseLabel.isBlank()
                ? (phase == null ? "IDLE" : phase.name())
                : phaseLabel);
        ph.put("remaining", remainingFormatted == null ? "" : remainingFormatted);

        if (configuredLines == null || configuredLines.isEmpty()) {
            return new ScoreboardView(List.of());
        }
        int maxIdx = -1;
        for (PluginConfig.ScoreboardLine line : configuredLines) {
            if (line != null) {
                maxIdx = Math.max(maxIdx, line.line());
            }
        }
        if (maxIdx < 0) {
            return new ScoreboardView(List.of());
        }
        List<String> rendered = new ArrayList<>(maxIdx + 1);
        for (int i = 0; i <= maxIdx; i++) {
            rendered.add(" ");
        }
        for (PluginConfig.ScoreboardLine line : configuredLines) {
            if (line == null || line.text() == null) {
                continue;
            }
            if (line.line() < 0 || line.line() > maxIdx) {
                continue;
            }
            String text = colorAmpersandToSection(TextUtil.apply(line.text(), ph));
            rendered.set(line.line(), text.isBlank() ? " " : text);
        }
        return new ScoreboardView(List.copyOf(rendered));
    }

    /**
     * @deprecated use {@link #renderScoreboardLines(List, EventPhase, String, String, String, int)}
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
     * Build initial template lines (indices padded) for TAB createScoreboard.
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
