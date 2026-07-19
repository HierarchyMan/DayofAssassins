package com.fusion.dev.cystol.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase machine + FFA moment rules — not bossbar copy or fill-progress re-derives beyond one check.
 */
class EventTimelineTest {

    @Test
    void ffaMomentOffsetAndOverride() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        assertEquals(Instant.parse("2026-01-01T13:30:00Z"),
                new EventTimeline(start, end, null, 1800).ffaMoment().orElseThrow());
        Instant ffa = Instant.parse("2026-01-01T13:00:00Z");
        assertEquals(ffa, new EventTimeline(start, end, ffa, 1800).ffaMoment().orElseThrow());
    }

    @Test
    void phasesAndKillWindow() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        assertEquals(EventPhase.COUNTDOWN, t.phaseAt(Instant.parse("2026-01-01T11:00:00Z")));
        assertEquals(EventPhase.HUNT, t.phaseAt(Instant.parse("2026-01-01T12:30:00Z")));
        assertEquals(EventPhase.FFA, t.phaseAt(Instant.parse("2026-01-01T13:30:00Z")));
        assertEquals(EventPhase.ENDED, t.phaseAt(Instant.parse("2026-01-01T14:00:00Z")));

        assertFalse(t.killsCountAt(Instant.parse("2026-01-01T11:00:00Z")));
        assertTrue(t.killsCountAt(Instant.parse("2026-01-01T12:00:00Z")));
        assertTrue(t.killsCountAt(Instant.parse("2026-01-01T13:45:00Z")));
        assertFalse(t.killsCountAt(Instant.parse("2026-01-01T14:00:00Z")));
    }

    @Test
    void fillProgressBounds() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        long lead = 3600;
        Instant windowStart = start.minusSeconds(lead);
        assertEquals(0.0, t.countdownFillProgress(windowStart.minusSeconds(10), lead), 1e-9);
        assertEquals(0.5, t.countdownFillProgress(windowStart.plusSeconds(lead / 2), lead), 1e-9);
        assertEquals(1.0, t.countdownFillProgress(start, lead), 1e-9);
        assertEquals(0.0, t.liveFillProgress(start), 1e-9);
        assertEquals(0.5, t.liveFillProgress(Instant.parse("2026-01-01T13:00:00Z")), 1e-9);
        assertEquals(1.0, t.liveFillProgress(end), 1e-9);
        assertEquals(3600, t.secondsUntilEnd(Instant.parse("2026-01-01T13:00:00Z")));
    }

    @Test
    void secondsUntilNextPhaseIsPhaseBoundaryNotTotalEnd() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        // FFA at 13:30
        EventTimeline t = new EventTimeline(start, end, null, 1800);

        // COUNTDOWN: until start
        assertEquals(3600, t.secondsUntilNextPhase(Instant.parse("2026-01-01T11:00:00Z")));
        // HUNT mid: until FFA (13:30), not until end (14:00)
        assertEquals(1800, t.secondsUntilNextPhase(Instant.parse("2026-01-01T13:00:00Z")));
        assertEquals(3600, t.secondsUntilEnd(Instant.parse("2026-01-01T13:00:00Z")));
        // FFA: until end
        assertEquals(1800, t.secondsUntilNextPhase(Instant.parse("2026-01-01T13:30:00Z")));
        // ENDED
        assertEquals(0, t.secondsUntilNextPhase(Instant.parse("2026-01-01T14:00:00Z")));
    }

    @Test
    void cosmeticGraceWindowDoesNotChangePhase() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        long grace = 600; // 10m

        // Far before grace
        Instant early = Instant.parse("2026-01-01T11:00:00Z");
        assertEquals(EventPhase.COUNTDOWN, t.phaseAt(early));
        assertFalse(t.inGraceWindow(early, true, grace));

        // Exactly at grace open (start − 600s)
        Instant graceOpen = start.minusSeconds(grace);
        assertEquals(EventPhase.COUNTDOWN, t.phaseAt(graceOpen));
        assertTrue(t.inGraceWindow(graceOpen, true, grace));
        assertEquals(0.0, t.graceFillProgress(graceOpen, grace), 1e-9);

        // Mid grace
        Instant mid = start.minusSeconds(300);
        assertEquals(EventPhase.COUNTDOWN, t.phaseAt(mid));
        assertTrue(t.inGraceWindow(mid, true, grace));
        assertEquals(0.5, t.graceFillProgress(mid, grace), 1e-9);

        // Disabled / zero length
        assertFalse(t.inGraceWindow(mid, false, grace));
        assertFalse(t.inGraceWindow(mid, true, 0));

        // At hunt start — real phase flips; grace off
        assertEquals(EventPhase.HUNT, t.phaseAt(start));
        assertFalse(t.inGraceWindow(start, true, grace));
        assertEquals(1.0, t.graceFillProgress(start, grace), 1e-9);
    }

    @Test
    void phaseFillProgressDrainsHuntAndFfa() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        // HUNT half-way start→FFA (12:00→13:30) at 12:45 → drain 0.5
        assertEquals(0.5, t.phaseFillProgress(Instant.parse("2026-01-01T12:45:00Z"), 3600), 1e-9);
        // FFA half-way 13:30→14:00 at 13:45 → drain 0.5
        assertEquals(0.5, t.phaseFillProgress(Instant.parse("2026-01-01T13:45:00Z"), 3600), 1e-9);
    }

    @Test
    void bossBarProgressFillCountdownDrainLive() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        Instant anchor = Instant.parse("2026-01-01T10:00:00Z"); // 2h before start
        Instant midCd = Instant.parse("2026-01-01T11:00:00Z");
        assertEquals(0.5, t.bossBarProgress(midCd, anchor, null, null), 1e-9);

        Instant huntEnter = start;
        Instant midHunt = Instant.parse("2026-01-01T12:45:00Z"); // half of 90m to FFA
        assertEquals(0.5, t.bossBarProgress(midHunt, anchor, huntEnter, null), 1e-9);

        Instant ffaEnter = Instant.parse("2026-01-01T13:30:00Z");
        Instant midFfa = Instant.parse("2026-01-01T13:45:00Z");
        assertEquals(0.5, t.bossBarProgress(midFfa, anchor, huntEnter, ffaEnter), 1e-9);
        assertEquals(0.0, t.bossBarProgress(end, anchor, huntEnter, ffaEnter), 1e-9);
    }
}
