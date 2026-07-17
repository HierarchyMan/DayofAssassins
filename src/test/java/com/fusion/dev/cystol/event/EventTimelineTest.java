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
    void phaseFillProgressTracksCurrentSegment() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        // HUNT half-way start→FFA (12:00→13:30) at 12:45
        assertEquals(0.5, t.phaseFillProgress(Instant.parse("2026-01-01T12:45:00Z"), 3600), 1e-9);
        // FFA half-way 13:30→14:00 at 13:45
        assertEquals(0.5, t.phaseFillProgress(Instant.parse("2026-01-01T13:45:00Z"), 3600), 1e-9);
    }
}
