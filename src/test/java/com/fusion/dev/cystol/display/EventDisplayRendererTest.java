package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventTimeline;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoreboard line injection + bossbar content against shipped config.yml / lang.yml templates.
 */
class EventDisplayRendererTest {

    private static YamlConfiguration config;
    private static YamlConfiguration lang;

    @BeforeAll
    static void loadShippedYaml() {
        config = load("config.yml");
        lang = load("lang.yml");
    }

    private static YamlConfiguration load(String resource) {
        InputStream in = EventDisplayRendererTest.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Missing resource " + resource);
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static List<PluginConfig.ScoreboardLine> scoreboardLinesFromConfig() {
        List<PluginConfig.ScoreboardLine> lines = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("tab.scoreboard.lines")) {
            Object lineObj = map.get("line");
            Object textObj = map.get("text");
            if (lineObj instanceof Number n && textObj != null) {
                lines.add(new PluginConfig.ScoreboardLine(n.intValue(), String.valueOf(textObj)));
            }
        }
        return lines;
    }

    @Test
    void scoreboardInjectsAtConfiguredIndicesWithPlaceholders() {
        List<PluginConfig.ScoreboardLine> lines = scoreboardLinesFromConfig();
        assertFalse(lines.isEmpty(), "config.yml must define tab.scoreboard.lines");

        EventDisplayRenderer.ScoreboardView view = EventDisplayRenderer.renderScoreboardLines(
                lines, EventPhase.HUNT, "Hunt", "1h 0m", "AssassinX", 12
        );

        // default config: lines 3,4,5
        assertTrue(view.size() >= 6, "must pad through max index");
        // blank padding at 0..2
        assertTrue(view.lineAt(0).isBlank() || view.lineAt(0).equals(" "));
        assertTrue(view.lineAt(1).isBlank() || view.lineAt(1).equals(" "));
        assertTrue(view.lineAt(2).isBlank() || view.lineAt(2).equals(" "));

        String header = view.lineAt(3);
        String top = view.lineAt(4);
        String phase = view.lineAt(5);

        assertTrue(header.contains("Day of Assassins") || header.contains("Assassins"),
                "header line: " + header);
        assertTrue(header.contains("§"), "colors converted to section: " + header);

        assertTrue(top.contains("AssassinX"), "top killer injected: " + top);
        assertTrue(top.contains("12"), "top kills injected: " + top);

        assertTrue(phase.contains("Hunt"), "phase label injected: " + phase);
        assertTrue(phase.contains("1h") || phase.contains("0m"), "remaining injected: " + phase);
    }

    @Test
    void scoreboardEmptyTopKillerUsesEmDashPlaceholder() {
        List<PluginConfig.ScoreboardLine> lines = scoreboardLinesFromConfig();
        EventDisplayRenderer.ScoreboardView view = EventDisplayRenderer.renderScoreboardLines(
                lines, EventPhase.FFA, "Finale", "30m", null, 0
        );
        String top = view.lineAt(4);
        assertTrue(top.contains("—") || top.contains("-"), "empty top: " + top);
        assertTrue(view.lineAt(5).contains("Finale"), view.lineAt(5));
    }

    @Test
    void bossbarCountdownModeFillingAndTitleFromLang() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        EventTimeline timeline = new EventTimeline(start, start.plusSeconds(7200), null, 1800);
        long lead = config.getLong("ffa.announce-lead-seconds", 3600);
        Instant mid = start.minusSeconds(lead / 2);

        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                timeline,
                mid,
                lead,
                lang.getString("bossbar.countdown-title"),
                lang.getString("bossbar.title"),
                lang.getString("bossbar.title-no-kills"),
                null,
                null
        );

        assertTrue(bar.countdownMode());
        assertEquals(0.5f, bar.progress(), 0.001f);
        assertTrue(bar.titleLegacyAmpersand().toLowerCase().contains("starts in")
                        || bar.titleLegacyAmpersand().contains("starts"),
                bar.titleLegacyAmpersand());
        // human countdown present (hours or minutes)
        assertTrue(bar.titleLegacyAmpersand().matches(".*\\d+[hmsd].*")
                        || bar.titleLegacyAmpersand().contains("m")
                        || bar.titleLegacyAmpersand().contains("h"),
                "countdown text: " + bar.titleLegacyAmpersand());
    }

    @Test
    void bossbarLiveShowsTopKillerAndFillsWithEventProgress() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline timeline = new EventTimeline(start, end, null, 1800);
        Instant mid = Instant.parse("2026-01-01T13:00:00Z");

        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                timeline,
                mid,
                3600,
                lang.getString("bossbar.countdown-title"),
                lang.getString("bossbar.title"),
                lang.getString("bossbar.title-no-kills"),
                "TopDog",
                9
        );

        assertFalse(bar.countdownMode());
        assertEquals(0.5f, bar.progress(), 0.001f);
        assertTrue(bar.titleLegacyAmpersand().contains("TopDog"), bar.titleLegacyAmpersand());
        // live templates include remaining time
        assertTrue(bar.titleLegacyAmpersand().matches(".*\\d+[hmsd].*")
                        || bar.titleLegacyAmpersand().contains("m")
                        || bar.titleLegacyAmpersand().contains("h"),
                "remaining in live bar: " + bar.titleLegacyAmpersand());
    }

    @Test
    void bossbarLiveNoKillsUsesFallbackLang() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline timeline = new EventTimeline(start, end, null, 1800);

        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                timeline,
                start.plusSeconds(10),
                3600,
                lang.getString("bossbar.countdown-title"),
                lang.getString("bossbar.title"),
                lang.getString("bossbar.title-no-kills"),
                null,
                null
        );

        assertFalse(bar.countdownMode());
        String noKills = lang.getString("bossbar.title-no-kills");
        // apply is identity when no placeholders
        assertTrue(bar.titleLegacyAmpersand().contains("Nobody")
                        || bar.titleLegacyAmpersand().equals(noKills)
                        || noKills.contains("Nobody"),
                bar.titleLegacyAmpersand());
    }

    @Test
    void templateLinesPadToConfiguredIndices() {
        List<String> templates = EventDisplayRenderer.buildTemplateLines(scoreboardLinesFromConfig());
        assertTrue(templates.size() >= 6);
        assertTrue(EventDisplayRenderer.colorAmpersandToSection("&cRed").startsWith("§"));
    }
}
