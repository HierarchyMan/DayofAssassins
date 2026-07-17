package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.event.EventPhase;

/**
 * Whether a PvP death should credit a kill for the current phase / location.
 * Time window is handled separately ({@code killsCountAt}); this is location policy only.
 */
public final class KillCreditRules {

    private KillCreditRules() {
    }

    /**
     * Hunt: kills count anywhere.
     * FFA: both killer and victim must be inside the arena cuboid.
     */
    public static boolean locationAllowsCredit(
            EventPhase phase,
            boolean killerInArena,
            boolean victimInArena
    ) {
        if (phase == EventPhase.HUNT) {
            return true;
        }
        if (phase == EventPhase.FFA) {
            return killerInArena && victimInArena;
        }
        return false;
    }
}
