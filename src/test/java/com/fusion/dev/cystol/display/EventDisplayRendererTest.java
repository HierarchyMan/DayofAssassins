package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventTimeline;
import com.fusion.dev.cystol.kill.DenseRanking;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDisplayRendererTest {

    private static final EventDisplayRenderer.TimerLabels TIMERS =
            new EventDisplayRenderer.TimerLabels(
                    "Starts in", "Hunt opens in", "Finale in", "Ends in"
            );

    @Test
    void topThreeInjectsOnlyConfiguredIndices() {
        List<PluginConfig.ScoreboardLine> cfg = List.of(
                new PluginConfig.ScoreboardLine(3, "&c#1 &f%top1_name% &8(%top1_kills%)"),
                new PluginConfig.ScoreboardLine(4, "&6#2 &f%top2_name% &8(%top2_kills%)"),
                new PluginConfig.ScoreboardLine(5, "&e#3 &f%top3_name% &8(%top3_kills%)"),
                new PluginConfig.ScoreboardLine(6, "&7%phase% &8| &f%remaining%")
        );
        List<DenseRanking.Entry> ranking = List.of(
                new DenseRanking.Entry(UUID.randomUUID(), "Alice", 12, 1),
                new DenseRanking.Entry(UUID.randomUUID(), "Bob", 9, 2),
                new DenseRanking.Entry(UUID.randomUUID(), "Carol", 4, 3)
        );
        Map<Integer, String> inj = EventDisplayRenderer.renderScoreboardInjections(
                cfg,
                EventPhase.HUNT,
                "Hunt",
                "25m",
                "1h 25m",
                ranking,
                3,
                "—",
                "0"
        );
        assertEquals(4, inj.size());
        assertFalse(inj.containsKey(0));
        assertFalse(inj.containsKey(1));
        assertFalse(inj.containsKey(2));
        assertTrue(inj.get(3).contains("Alice"));
        assertTrue(inj.get(3).contains("12"));
        assertTrue(inj.get(4).contains("Bob"));
        assertTrue(inj.get(4).contains("9"));
        assertTrue(inj.get(5).contains("Carol"));
        assertTrue(inj.get(5).contains("4"));
        assertTrue(inj.get(6).contains("Hunt"));
        assertTrue(inj.get(6).contains("25m"));
        // phase remaining must not silently swap in until_end
        assertFalse(inj.get(6).contains("1h 25m"));
    }

    @Test
    void emptyTopSlotsUseConfiguredPlaceholders() {
        List<PluginConfig.ScoreboardLine> cfg = List.of(
                new PluginConfig.ScoreboardLine(0, "%top1_name%"),
                new PluginConfig.ScoreboardLine(1, "%top2_name%"),
                new PluginConfig.ScoreboardLine(2, "%top3_kills%")
        );
        List<DenseRanking.Entry> ranking = List.of(
                new DenseRanking.Entry(UUID.randomUUID(), "Solo", 3, 1)
        );
        Map<Integer, String> inj = EventDisplayRenderer.renderScoreboardInjections(
                cfg, EventPhase.FFA, "Finale", "10m", "10m", ranking, 3, "—", "0"
        );
        assertTrue(inj.get(0).contains("Solo"));
        assertTrue(inj.get(1).contains("—"));
        assertTrue(inj.get(2).contains("0"));
    }

    @Test
    void topKillerAliasMatchesSlotOne() {
        List<EventDisplayRenderer.TopSlot> tops = EventDisplayRenderer.topSlots(
                List.of(new DenseRanking.Entry(UUID.randomUUID(), "Zed", 5, 1)),
                3
        );
        Map<String, String> ph = EventDisplayRenderer.buildPlaceholders(
                EventPhase.HUNT, "Hunt", "1m", "2h", tops, 3, "—", "0"
        );
        assertEquals("Zed", ph.get("top1_name"));
        assertEquals("Zed", ph.get("top_killer"));
        assertEquals("5", ph.get("top1_kills"));
        assertEquals("5", ph.get("top_kills"));
        assertEquals("—", ph.get("top2_name"));
        assertEquals("—", ph.get("top3_name"));
    }

    @Test
    void progressPercentMaps01To0100() {
        assertEquals(0f, EventDisplayRenderer.progressPercent(0f), 0.001f);
        assertEquals(50f, EventDisplayRenderer.progressPercent(0.5f), 0.001f);
        assertEquals(100f, EventDisplayRenderer.progressPercent(1f), 0.001f);
        assertEquals(100f, EventDisplayRenderer.progressPercent(2f), 0.001f);
        assertEquals(0f, EventDisplayRenderer.progressPercent(-1f), 0.001f);
    }

    @Test
    void graceBossBarUsesGraceTitleAndDoesNotRequireLiveKillers() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        Instant midGrace = start.minusSeconds(300);
        // Progress is anchor-driven (caller supplies); title still grace-specific
        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                t,
                midGrace,
                "&cstarts in %countdown%",
                "&clive %top_killer%",
                "&cnokills",
                "&clive %top_killer%",
                "&cnokills",
                null,
                null,
                true,
                600,
                "&aGrace %countdown%",
                TIMERS,
                0.75
        );
        assertTrue(bar.graceMode());
        assertTrue(bar.countdownMode());
        assertTrue(bar.titleLegacyAmpersand().contains("Grace"));
        assertFalse(bar.titleLegacyAmpersand().contains("starts in"));
        assertEquals(0.75f, bar.progress(), 0.001f);
    }

    @Test
    void graceDisabledKeepsNormalCountdownTitle() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        Instant midGrace = start.minusSeconds(300);
        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                t,
                midGrace,
                3600,
                "&cstarts in %countdown%",
                "&clive",
                "&cnokills",
                null,
                null,
                false,
                600,
                "&aGrace %countdown%"
        );
        assertFalse(bar.graceMode());
        assertTrue(bar.countdownMode());
        assertTrue(bar.titleLegacyAmpersand().contains("starts in"));
    }

    @Test
    void huntBossBarNamesFinaleDestinationViaTimerLabel() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        Instant duringHunt = Instant.parse("2026-01-01T12:30:00Z");
        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                t,
                duringHunt,
                "&cstarts in %countdown%",
                "&cHunt bar | %timer_label% %remaining%",
                "&cHunt empty | %timer_label% %remaining%",
                "&cFFA bar | %timer_label% %remaining%",
                "&cFFA empty | %timer_label% %remaining%",
                "Alice",
                3,
                false,
                0L,
                null,
                TIMERS,
                0.5
        );
        assertFalse(bar.countdownMode());
        assertTrue(bar.titleLegacyAmpersand().contains("Hunt bar"));
        assertTrue(bar.titleLegacyAmpersand().contains("Finale in"));
        assertFalse(bar.titleLegacyAmpersand().contains("FFA bar"));
        assertFalse(bar.titleLegacyAmpersand().toLowerCase().contains(" next "));
    }

    @Test
    void ffaBossBarNamesEndDestinationNotHunt() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        Instant duringFfa = Instant.parse("2026-01-01T13:40:00Z");
        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                t,
                duringFfa,
                "&cstarts in %countdown%",
                "&cHunt bar | %timer_label% %remaining%",
                "&cHunt empty | %timer_label% %remaining%",
                "&cFFA bar | %timer_label% %remaining%",
                "&cFFA empty | %timer_label% %remaining%",
                null,
                null,
                false,
                0L,
                null,
                TIMERS,
                0.4
        );
        assertFalse(bar.countdownMode());
        assertTrue(bar.titleLegacyAmpersand().contains("FFA empty"));
        assertTrue(bar.titleLegacyAmpersand().contains("Ends in"));
        assertFalse(bar.titleLegacyAmpersand().contains("Hunt"));
        assertFalse(bar.titleLegacyAmpersand().contains("Finale in"));
    }

    @Test
    void ffaNeverCrossFallsBackToHuntTemplateSayingFinaleIn() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        Instant duringFfa = Instant.parse("2026-01-01T13:40:00Z");
        // Blank FFA templates — must use hard default with Ends-in timer, not hunt "Finale in"
        EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                t,
                duringFfa,
                "&cstarts in %countdown%",
                "&cHUNT-ONLY Finale in %remaining%",
                "&cHUNT-ONLY empty Finale in %remaining%",
                "",
                "",
                "Bob",
                2,
                false,
                0L,
                null,
                TIMERS,
                0.2
        );
        assertFalse(bar.titleLegacyAmpersand().contains("HUNT-ONLY"));
        assertTrue(bar.titleLegacyAmpersand().contains("Ends in"));
        assertFalse(bar.titleLegacyAmpersand().contains("Finale in"));
    }

    @Test
    void timerLabelNamesDestinationPerPhase() {
        assertEquals("Starts in", EventDisplayRenderer.timerLabelForPhase(
                EventPhase.COUNTDOWN, false, TIMERS));
        assertEquals("Hunt opens in", EventDisplayRenderer.timerLabelForPhase(
                EventPhase.COUNTDOWN, true, TIMERS));
        assertEquals("Finale in", EventDisplayRenderer.timerLabelForPhase(
                EventPhase.HUNT, false, TIMERS));
        assertEquals("Ends in", EventDisplayRenderer.timerLabelForPhase(
                EventPhase.FFA, false, TIMERS));
    }

    @Test
    void phaseLabelMatrix() {
        EventDisplayRenderer.PhaseLabels labels = EventDisplayRenderer.PhaseLabels.defaults();
        assertEquals("Starting soon", EventDisplayRenderer.phaseLabel(EventPhase.COUNTDOWN, false, labels));
        assertEquals("Grace", EventDisplayRenderer.phaseLabel(EventPhase.COUNTDOWN, true, labels));
        assertEquals("Hunt", EventDisplayRenderer.phaseLabel(EventPhase.HUNT, false, labels));
        assertEquals("Finale", EventDisplayRenderer.phaseLabel(EventPhase.FFA, false, labels));
        assertEquals("Finale (FFA)", EventDisplayRenderer.opsPhaseLabel(EventPhase.FFA, labels));
        assertEquals("Hunt", EventDisplayRenderer.opsPhaseLabel(EventPhase.HUNT, labels));
    }

    @Test
    void scoreboardInjectsTimerLabelNotBareNext() {
        List<PluginConfig.ScoreboardLine> cfg = List.of(
                new PluginConfig.ScoreboardLine(0, "&7%phase% · %timer_label% %remaining%")
        );
        Map<Integer, String> hunt = EventDisplayRenderer.renderScoreboardInjections(
                cfg, EventPhase.HUNT, "Hunt", "25m", "1h 25m", "Finale in",
                List.of(), 3, "—", "0"
        );
        assertTrue(hunt.get(0).contains("Hunt"));
        assertTrue(hunt.get(0).contains("Finale in"));
        assertTrue(hunt.get(0).contains("25m"));
        assertFalse(hunt.get(0).toLowerCase().contains("next"));

        Map<Integer, String> ffa = EventDisplayRenderer.renderScoreboardInjections(
                cfg, EventPhase.FFA, "Finale", "10m", "10m", "Ends in",
                List.of(), 3, "—", "0"
        );
        assertTrue(ffa.get(0).contains("Finale"));
        assertTrue(ffa.get(0).contains("Ends in"));
        assertFalse(ffa.get(0).contains("Hunt"));
    }

    @Test
    void legacyNextRemainingScoreboardLineIsNormalizedAtRender() {
        // Deployed configs still have "next %remaining%" — must upgrade at render time
        List<PluginConfig.ScoreboardLine> legacy = List.of(
                new PluginConfig.ScoreboardLine(0, "&7%phase% &8· next &f%remaining%")
        );
        Map<Integer, String> hunt = EventDisplayRenderer.renderScoreboardInjections(
                legacy, EventPhase.HUNT, "Hunt", "25m", "1h", "Finale in",
                List.of(), 3, "—", "0"
        );
        assertTrue(hunt.get(0).contains("Finale in"));
        assertTrue(hunt.get(0).contains("25m"));
        assertFalse(hunt.get(0).toLowerCase().contains("next"));

        Map<Integer, String> ffa = EventDisplayRenderer.renderScoreboardInjections(
                legacy, EventPhase.FFA, "Finale", "10m", "10m", "Ends in",
                List.of(), 3, "—", "0"
        );
        assertTrue(ffa.get(0).contains("Ends in"));
        assertFalse(ffa.get(0).toLowerCase().contains("next"));
    }

    @Test
    void normalizeScoreboardTemplateIsIdempotentWithTimerLabel() {
        String modern = "&7%phase% &8· &7%timer_label% &f%remaining%";
        assertEquals(modern, EventDisplayRenderer.normalizeScoreboardTemplate(modern));
        String upgraded = EventDisplayRenderer.normalizeScoreboardTemplate(
                "&7%phase% &8· next &f%remaining%"
        );
        assertTrue(upgraded.contains("%timer_label%"));
        assertFalse(upgraded.toLowerCase().contains("next"));
        assertEquals(upgraded, EventDisplayRenderer.normalizeScoreboardTemplate(upgraded));
    }

    @Test
    void fullPhaseHudMatrixRemainingSemantics() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z"); // ffa @ 13:30
        EventTimeline t = new EventTimeline(start, end, null, 1800);

        Instant countdown = Instant.parse("2026-01-01T11:00:00Z");
        assertEquals(EventPhase.COUNTDOWN, t.phaseAt(countdown));
        assertEquals(3600L, t.secondsUntilNextPhase(countdown));

        Instant hunt = Instant.parse("2026-01-01T13:00:00Z");
        assertEquals(EventPhase.HUNT, t.phaseAt(hunt));
        assertEquals(1800L, t.secondsUntilNextPhase(hunt)); // until FFA, not end
        assertEquals(3600L, t.secondsUntilEnd(hunt));

        Instant ffa = Instant.parse("2026-01-01T13:45:00Z");
        assertEquals(EventPhase.FFA, t.phaseAt(ffa));
        assertEquals(900L, t.secondsUntilNextPhase(ffa)); // until end
        assertEquals(900L, t.secondsUntilEnd(ffa));
    }
}
