package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.event.EventPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillCreditRulesTest {

    @Test
    void huntCountsAnywhere() {
        assertTrue(KillCreditRules.locationAllowsCredit(EventPhase.HUNT, false, false));
        assertTrue(KillCreditRules.locationAllowsCredit(EventPhase.HUNT, true, false));
        assertTrue(KillCreditRules.locationAllowsCredit(EventPhase.HUNT, false, true));
    }

    @Test
    void ffaRequiresBothInArena() {
        assertFalse(KillCreditRules.locationAllowsCredit(EventPhase.FFA, false, false));
        assertFalse(KillCreditRules.locationAllowsCredit(EventPhase.FFA, true, false));
        assertFalse(KillCreditRules.locationAllowsCredit(EventPhase.FFA, false, true));
        assertTrue(KillCreditRules.locationAllowsCredit(EventPhase.FFA, true, true));
    }

    @Test
    void otherPhasesNever() {
        assertFalse(KillCreditRules.locationAllowsCredit(EventPhase.COUNTDOWN, true, true));
        assertFalse(KillCreditRules.locationAllowsCredit(EventPhase.ENDED, true, true));
        assertFalse(KillCreditRules.locationAllowsCredit(EventPhase.IDLE, true, true));
        assertFalse(KillCreditRules.locationAllowsCredit(EventPhase.PAUSED, true, true));
    }
}
