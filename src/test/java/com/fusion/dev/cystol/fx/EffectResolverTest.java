package com.fusion.dev.cystol.fx;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UX FX plans resolved from shipped config.yml for every EffectKey.
 */
class EffectResolverTest {

    private static YamlConfiguration config;

    @BeforeAll
    static void load() {
        InputStream in = EffectResolverTest.class.getClassLoader().getResourceAsStream("config.yml");
        if (in == null) {
            throw new IllegalStateException("config.yml missing from test resources");
        }
        config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test
    void masterEnabledAndAllKeysResolvePlayablePlans() {
        assertTrue(config.getBoolean("effects.enabled", false));

        for (EffectService.EffectKey key : EffectService.EffectKey.values()) {
            EffectPlan plan = EffectResolver.resolve(config, key);
            assertTrue(plan.shouldPlay(), "effect should play: " + key + " path=" + key.path());
            // Every default key has a sound enabled in config.yml
            assertTrue(plan.hasSound(), "sound required for " + key + " got " + plan);
            assertFalse(plan.soundName().isBlank(), key.name());
            assertTrue(plan.volume() > 0f, key.name());
        }
    }

    @Test
    void compassOpenGuiHasSoundAndParticle() {
        EffectPlan plan = EffectResolver.resolve(config, EffectService.EffectKey.COMPASS_OPEN_GUI);
        assertTrue(plan.hasSound());
        assertTrue(plan.hasParticle());
        assertTrue(plan.soundName().contains("spyglass") || plan.soundName().startsWith("minecraft:"));
        assertEquals("ENCHANT", plan.particleName());
        assertTrue(plan.particleCount() > 0);
        assertTrue(EffectResolver.isKnownParticleName(plan.particleName()));
    }

    @Test
    void menuSelectAndKillHaveParticlesWhenConfigured() {
        EffectPlan select = EffectResolver.resolve(config, EffectService.EffectKey.MENU_SELECT_TARGET);
        assertTrue(select.hasParticle());
        assertEquals("HAPPY_VILLAGER", select.particleName());

        EffectPlan kill = EffectResolver.resolve(config, EffectService.EffectKey.KILL_CREDITED);
        assertTrue(kill.hasSound());
        assertTrue(kill.hasParticle());
        assertEquals("SOUL", kill.particleName());
    }

    @Test
    void pageTurnParticleCanBeDisabledWhileSoundPlays() {
        EffectPlan page = EffectResolver.resolve(config, EffectService.EffectKey.MENU_PAGE);
        assertTrue(page.soundEnabled(), () -> "expected sound enabled, plan=" + page);
        assertTrue(page.hasSound(), () -> "expected hasSound, plan=" + page);
        assertFalse(page.particleEnabled(), () -> "particle should be disabled, plan=" + page);
        assertFalse(page.hasParticle(), () -> "hasParticle should be false, plan=" + page);
        assertTrue(page.soundName().contains("page_turn") || page.soundName().contains("book"),
                () -> "unexpected sound: " + page.soundName());
    }

    @Test
    void masterDisabledDisablesAllEffects() throws Exception {
        // Deep copy via string so we never share ConfigurationSection refs with static config
        YamlConfiguration clone = new YamlConfiguration();
        clone.loadFromString(config.saveToString());
        clone.set("effects.enabled", false);
        EffectPlan plan = EffectResolver.resolve(clone, EffectService.EffectKey.END_TOP3);
        assertFalse(plan.shouldPlay());
        assertFalse(plan.hasSound());
        assertFalse(plan.hasParticle());
        // static shipped config must remain enabled for other tests
        assertTrue(config.getBoolean("effects.enabled"));
    }

    @Test
    void endTop3AndNormalAreDistinctUxKeys() {
        EffectPlan normal = EffectResolver.resolve(config, EffectService.EffectKey.END_NORMAL);
        EffectPlan top3 = EffectResolver.resolve(config, EffectService.EffectKey.END_TOP3);
        assertTrue(normal.hasSound());
        assertTrue(top3.hasSound());
        // heroic pitch higher by default
        assertTrue(top3.pitch() >= normal.pitch());
    }

    @Test
    void missingSectionYieldsDisabledPlan() {
        EffectPlan plan = EffectResolver.resolve(true, null);
        assertFalse(plan.shouldPlay());
    }
}
