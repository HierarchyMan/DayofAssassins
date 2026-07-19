package com.fusion.dev.cystol.kill;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sliding-window same-victim farm gate (max credits in window).
 */
class KillFarmGuardTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final long WINDOW = 300_000L;

    @Test
    void pureFirstCreditAllowedSecondBlockedWhenMaxOne() {
        ArrayDeque<Long> q = new ArrayDeque<>();
        long t0 = 1_000_000L;
        assertTrue(KillFarmGuard.allowCreditPure(q, t0, WINDOW, 1));
        assertEquals(1, q.size());
        assertFalse(KillFarmGuard.allowCreditPure(q, t0 + 1_000L, WINDOW, 1));
        assertEquals(1, q.size());
    }

    @Test
    void pureWindowExpiryAllowsAgain() {
        ArrayDeque<Long> q = new ArrayDeque<>();
        long t0 = 1_000_000L;
        assertTrue(KillFarmGuard.allowCreditPure(q, t0, WINDOW, 1));
        assertTrue(KillFarmGuard.allowCreditPure(q, t0 + WINDOW + 1, WINDOW, 1));
        assertEquals(1, q.size());
    }

    @Test
    void instanceSameVictimBlockedDifferentVictimOk() {
        KillFarmGuard g = new KillFarmGuard();
        long t0 = 5_000_000L;
        assertTrue(g.tryRegisterCredit(A, B, 1, WINDOW, t0));
        assertFalse(g.tryRegisterCredit(A, B, 1, WINDOW, t0 + 100));
        UUID c = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        assertTrue(g.tryRegisterCredit(A, c, 1, WINDOW, t0 + 100));
    }

    @Test
    void clearWipesPairs() {
        KillFarmGuard g = new KillFarmGuard();
        assertTrue(g.tryRegisterCredit(A, B, 1, WINDOW, 1_000L));
        g.clear();
        assertTrue(g.tryRegisterCredit(A, B, 1, WINDOW, 2_000L));
    }

    @Test
    void remainingBlockPositiveWhileBlocked() {
        KillFarmGuard g = new KillFarmGuard();
        long t0 = 10_000_000L;
        g.tryRegisterCredit(A, B, 1, WINDOW, t0);
        long remain = g.remainingBlockMs(A, B, 1, WINDOW, t0 + 60_000L);
        assertTrue(remain > 0L);
        assertTrue(remain <= WINDOW - 60_000L + 1);
    }
}
