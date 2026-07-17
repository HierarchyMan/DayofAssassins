package com.fusion.dev.cystol.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTimelineTest {

    @Test
    void ffaMomentIsEndMinusOffsetWhenNoOverride() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        assertEquals(Instant.parse("2026-01-01T13:30:00Z"), t.ffaMoment().orElseThrow());
    }

    @Test
    void ffaOverrideWinsWhenBeforeEnd() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        Instant ffa = Instant.parse("2026-01-01T13:00:00Z");
        EventTimeline t = new EventTimeline(start, end, ffa, 1800);
        assertEquals(ffa, t.ffaMoment().orElseThrow());
    }

    @Test
    void phasesAcrossTimeline() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        assertEquals(EventPhase.COUNTDOWN, t.phaseAt(Instant.parse("2026-01-01T11:00:00Z")));
        assertEquals(EventPhase.HUNT, t.phaseAt(Instant.parse("2026-01-01T12:30:00Z")));
        assertEquals(EventPhase.FFA, t.phaseAt(Instant.parse("2026-01-01T13:30:00Z")));
        assertEquals(EventPhase.ENDED, t.phaseAt(Instant.parse("2026-01-01T14:00:00Z")));
    }

    @Test
    void killsCountOnlyHuntAndFfa() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        assertFalse(t.killsCountAt(Instant.parse("2026-01-01T11:00:00Z")));
        assertTrue(t.killsCountAt(Instant.parse("2026-01-01T12:00:00Z")));
        assertTrue(t.killsCountAt(Instant.parse("2026-01-01T13:45:00Z")));
        assertFalse(t.killsCountAt(Instant.parse("2026-01-01T14:00:00Z")));
    }

    @Test
    void countdownFillProgressFillsTowardStart() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        EventTimeline t = new EventTimeline(start, null, null, 1800);
        long lead = 3600;
        Instant windowStart = start.minusSeconds(lead);
        assertEquals(0.0, t.countdownFillProgress(windowStart.minusSeconds(10), lead), 1e-9);
        assertEquals(0.0, t.countdownFillProgress(windowStart, lead), 1e-9);
        Instant mid = windowStart.plusSeconds(lead / 2);
        assertEquals(0.5, t.countdownFillProgress(mid, lead), 1e-9);
        assertEquals(1.0, t.countdownFillProgress(start, lead), 1e-9);
        assertEquals(1.0, t.countdownFillProgress(start.plusSeconds(5), lead), 1e-9);
    }

    @Test
    void liveFillProgressFillsStartToEnd() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = Instant.parse("2026-01-01T14:00:00Z");
        EventTimeline t = new EventTimeline(start, end, null, 1800);
        assertEquals(0.0, t.liveFillProgress(start), 1e-9);
        assertEquals(0.5, t.liveFillProgress(Instant.parse("2026-01-01T13:00:00Z")), 1e-9);
        assertEquals(1.0, t.liveFillProgress(end), 1e-9);
    }
}
