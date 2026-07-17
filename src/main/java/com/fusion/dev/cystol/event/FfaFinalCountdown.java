package com.fusion.dev.cystol.event;

import java.util.OptionalInt;
import java.util.Set;

/**
 * Pure helpers for the last-seconds FFA title countdown (5…1) before ring TP.
 */
public final class FfaFinalCountdown {

    private FfaFinalCountdown() {
    }

    /**
     * Which remaining-second digit to show now, if any.
     * Only values in {@code [1, fromSeconds]} are valid; each may fire once.
     *
     * @param secsToFfa    whole seconds until FFA moment (must be ≥ 1)
     * @param fromSeconds  highest number to show (e.g. 5 → 5,4,3,2,1)
     * @param alreadyFired seconds already announced this schedule
     */
    public static OptionalInt secondToAnnounce(long secsToFfa, int fromSeconds, Set<Integer> alreadyFired) {
        if (fromSeconds <= 0 || secsToFfa < 1 || secsToFfa > fromSeconds) {
            return OptionalInt.empty();
        }
        int n = (int) secsToFfa;
        if (alreadyFired != null && alreadyFired.contains(n)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(n);
    }

    /**
     * Rising pitch as the count approaches 1.
     * At {@code remaining == fromSeconds} → {@code pitchBase};
     * each lower second adds {@code pitchStep}, clamped to Minecraft's typical [0.5, 2.0].
     */
    public static float pitchForRemaining(float pitchBase, float pitchStep, int fromSeconds, int remaining) {
        if (fromSeconds <= 0) {
            return clampPitch(pitchBase);
        }
        int steps = Math.max(0, fromSeconds - remaining);
        return clampPitch(pitchBase + steps * pitchStep);
    }

    public static float clampPitch(float pitch) {
        if (Float.isNaN(pitch) || Float.isInfinite(pitch)) {
            return 1f;
        }
        return Math.max(0.5f, Math.min(2.0f, pitch));
    }
}
