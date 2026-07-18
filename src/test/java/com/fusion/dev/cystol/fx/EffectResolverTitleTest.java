package com.fusion.dev.cystol.fx;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectResolverTitleTest {

    @Test
    void resolvesTitleSubsection() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("effects.kill.credited.sound.enabled", true);
        cfg.set("effects.kill.credited.sound.sound", "minecraft:entity.player.levelup");
        cfg.set("effects.kill.credited.title.enabled", true);
        cfg.set("effects.kill.credited.title.fade-in", 100);
        cfg.set("effects.kill.credited.title.stay", 2000);
        cfg.set("effects.kill.credited.title.fade-out", 400);

        EffectPlan plan = EffectResolver.resolve(cfg, EffectService.EffectKey.KILL_CREDITED);
        assertTrue(plan.hasTitle());
        assertTrue(plan.shouldPlay());
        assertEquals(100, plan.titleFadeInMs());
        assertEquals(2000, plan.titleStayMs());
        assertEquals(400, plan.titleFadeOutMs());
    }

    @Test
    void titleDisabledByDefaultLeavesShouldPlayToSound() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("effects.kill.credited.sound.enabled", true);
        cfg.set("effects.kill.credited.sound.sound", "minecraft:entity.player.levelup");

        EffectPlan plan = EffectResolver.resolve(cfg, EffectService.EffectKey.KILL_CREDITED);
        assertFalse(plan.hasTitle());
        assertTrue(plan.shouldPlay());
    }

    @Test
    void titleOnlyEnablesShouldPlay() {
        ConfigurationSection root = new YamlConfiguration();
        root.set("title.enabled", true);
        root.set("title.fade-in", 250);
        root.set("title.stay", 1500);
        root.set("title.fade-out", 500);

        EffectPlan plan = EffectResolver.resolve(true, root);
        assertTrue(plan.hasTitle());
        assertTrue(plan.shouldPlay());
        assertFalse(plan.hasSound());
        assertFalse(plan.hasParticle());
        assertEquals(1500, plan.titleStayMs());
    }
}
