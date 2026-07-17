package com.fusion.dev.cystol.ux;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UX surface: critical lang keys present for menus, items, bossbar, ceremony.
 */
class LangUxCompletenessTest {

    private static YamlConfiguration lang;

    @BeforeAll
    static void load() {
        InputStream in = LangUxCompletenessTest.class.getClassLoader().getResourceAsStream("lang.yml");
        if (in == null) {
            throw new IllegalStateException("lang.yml missing");
        }
        lang = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test
    void compassItemStatesHaveNameAndLore() {
        for (String state : List.of("inactive", "tracking", "tracking-other-world", "lost-target")) {
            String base = "compass.item." + state;
            assertFalse(lang.getString(base + ".name", "").isBlank(), base + ".name");
            assertFalse(lang.getStringList(base + ".lore").isEmpty(), base + ".lore");
        }
    }

    @Test
    void compassGuiHasTitleAndPlayerLoreVariants() {
        assertFalse(lang.getString("compass.gui.title", "").isBlank());
        assertFalse(lang.getStringList("compass.gui.player.lore").isEmpty());
        assertFalse(lang.getStringList("compass.gui.player.lore-selected").isEmpty());
        assertFalse(lang.getString("compass.tracking-selected", "").isBlank());
        assertFalse(lang.getString("compass.other-world-actionbar", "").isBlank());
        assertTrue(lang.getString("compass.other-world-actionbar").contains("%world%"));
    }

    @Test
    void bossbarAndEndCeremonyKeysPresent() {
        assertTrue(lang.getString("bossbar.countdown-title").contains("%countdown%"));
        assertTrue(lang.getString("bossbar.title").contains("%top_killer%"));
        for (String place : List.of("place-1", "place-2", "place-3", "place-other")) {
            assertFalse(lang.getString("end.title." + place + ".title", "").isBlank(), place);
            assertTrue(lang.getString("end.title." + place + ".subtitle").contains("%kills%"), place);
        }
    }

    @Test
    void ffaUxKeysPresent() {
        assertFalse(lang.getString("ffa.announce-title", "").isBlank());
        assertTrue(lang.getString("ffa.announce-subtitle").contains("%countdown%"));
        assertTrue(lang.getString("ffa.final-countdown-title", "").contains("%seconds%"));
        assertFalse(lang.getString("ffa.final-countdown-subtitle", "").isBlank());
        assertFalse(lang.getString("ffa.start-title", "").isBlank());
        assertFalse(lang.getString("ffa.start-subtitle", "").isBlank());
        assertFalse(lang.getString("ffa.outside-actionbar", "").isBlank());
    }
}
