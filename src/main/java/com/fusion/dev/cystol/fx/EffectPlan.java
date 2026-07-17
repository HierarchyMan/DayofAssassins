package com.fusion.dev.cystol.fx;

/**
 * Resolved effect intent (sound + particle) for one {@link EffectService.EffectKey}.
 * Pure data — no Bukkit types — so unit tests can assert UX FX wiring.
 */
public record EffectPlan(
        boolean shouldPlay,
        boolean soundEnabled,
        String soundName,
        float volume,
        float pitch,
        boolean particleEnabled,
        String particleName,
        int particleCount,
        double offsetX,
        double offsetY,
        double offsetZ
) {
    public static EffectPlan disabled() {
        return new EffectPlan(false, false, "", 0f, 0f, false, "", 0, 0, 0, 0);
    }

    public boolean hasSound() {
        return shouldPlay && soundEnabled && soundName != null && !soundName.isBlank();
    }

    public boolean hasParticle() {
        return shouldPlay && particleEnabled && particleName != null && !particleName.isBlank();
    }
}
