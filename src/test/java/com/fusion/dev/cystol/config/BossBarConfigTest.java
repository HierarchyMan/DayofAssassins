package com.fusion.dev.cystol.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BossBarConfigTest {

    @Test
    void colorNamesNormalizeCaseAndRejectInvalid() {
        assertEquals("YELLOW", PluginConfig.normalizeBossBarColorName("yellow", "RED"));
        assertEquals("PURPLE", PluginConfig.normalizeBossBarColorName("PURPLE", "RED"));
        assertEquals("RED", PluginConfig.normalizeBossBarColorName("rainbow", "RED"));
        assertEquals("GREEN", PluginConfig.normalizeBossBarColorName(null, "green"));
        assertEquals("RED", PluginConfig.normalizeBossBarColorName("nope", "nope"));
    }

    @Test
    void styleAliasesMapToCanonical() {
        assertEquals("PROGRESS", PluginConfig.normalizeBossBarStyleName("SOLID", "PROGRESS"));
        assertEquals("PROGRESS", PluginConfig.normalizeBossBarStyleName("progress", "RED"));
        assertEquals("NOTCHED_10", PluginConfig.normalizeBossBarStyleName("SEGMENTED_10", "PROGRESS"));
        assertEquals("NOTCHED_6", PluginConfig.normalizeBossBarStyleName("notched_6", "PROGRESS"));
        assertEquals("PROGRESS", PluginConfig.normalizeBossBarStyleName("wavy", "PROGRESS"));
    }
}
