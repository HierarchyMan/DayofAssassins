package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.kill.DenseRanking;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDisplayRendererTest {

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
}
