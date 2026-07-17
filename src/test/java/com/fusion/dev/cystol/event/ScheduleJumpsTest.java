package com.fusion.dev.cystol.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin time-jumps must land on the intended phase even with stale overrides / short ends.
 */
class ScheduleJumpsTest {

    private static final long BEFORE = 1800L;
    private static final Instant NOW = Instant.parse("2026-07-20T18:00:00Z");

    @Test
    void startNowClearsOverrideAndLandsInHunt() {
        Instant staleEnd = NOW.plusSeconds(7200);
        ScheduleJumps.Times t = ScheduleJumps.startNow(NOW, staleEnd, BEFORE);
        assertNull(t.ffaOverride());
        EventTimeline timeline = new EventTimeline(t.start(), t.end(), t.ffaOverride(), BEFORE);
        assertEquals(EventPhase.HUNT, timeline.phaseAt(NOW));
        assertTrue(timeline.ffaMoment().orElseThrow().isAfter(NOW));
        assertTrue(timeline.killsCountAt(NOW));
    }

    @Test
    void startNowPushesEndWhenDerivedFfaWouldBePast() {
        Instant shortEnd = NOW.plusSeconds(60);
        ScheduleJumps.Times t = ScheduleJumps.startNow(NOW, shortEnd, BEFORE);
        EventTimeline timeline = new EventTimeline(t.start(), t.end(), t.ffaOverride(), BEFORE);
        assertEquals(EventPhase.HUNT, timeline.phaseAt(NOW));
        assertTrue(timeline.ffaMoment().orElseThrow().isAfter(NOW));
        assertTrue(t.end().isAfter(NOW));
    }

    @Test
    void ffaNowLandsInFfaWithOverride() {
        ScheduleJumps.Times t = ScheduleJumps.ffaNow(NOW, null, null, BEFORE);
        EventTimeline timeline = new EventTimeline(t.start(), t.end(), t.ffaOverride(), BEFORE);
        assertEquals(EventPhase.FFA, timeline.phaseAt(NOW));
        assertEquals(NOW, t.ffaOverride());
        assertTrue(t.end().isAfter(NOW));
        assertTrue(timeline.killsCountAt(NOW));
    }

    @Test
    void endNowLandsInEnded() {
        ScheduleJumps.Times t = ScheduleJumps.endNow(NOW, null);
        EventTimeline timeline = new EventTimeline(t.start(), t.end(), t.ffaOverride(), BEFORE);
        assertEquals(EventPhase.ENDED, timeline.phaseAt(NOW));
        assertEquals(false, timeline.killsCountAt(NOW));
    }

    @Test
    void countdownLandsBeforeStart() {
        ScheduleJumps.Times t = ScheduleJumps.countdown(NOW, null, 300L, BEFORE);
        EventTimeline timeline = new EventTimeline(t.start(), t.end(), t.ffaOverride(), BEFORE);
        assertEquals(EventPhase.COUNTDOWN, timeline.phaseAt(NOW));
        assertTrue(t.start().isAfter(NOW));
    }

    /**
     * Skeptic case: currentEnd after start but end−before ≤ start would skip HUNT at start.
     * Reproduce without fix: now=T, currentEnd=T+2000, lead=300, before=1800 → start=T+300, ffa=T+200.
     */
    @Test
    void countdownStaleEndStillHasHuntWindowAtStart() {
        Instant currentEnd = NOW.plusSeconds(2000);
        long lead = 300L;
        ScheduleJumps.Times t = ScheduleJumps.countdown(NOW, currentEnd, lead, BEFORE);
        EventTimeline timeline = new EventTimeline(t.start(), t.end(), t.ffaOverride(), BEFORE);

        assertEquals(EventPhase.COUNTDOWN, timeline.phaseAt(NOW));
        Instant atStart = t.start();
        assertEquals(EventPhase.HUNT, timeline.phaseAt(atStart),
                "at start must be HUNT, not FFA (derived FFA must be strictly after start)");
        assertTrue(timeline.ffaMoment().orElseThrow().isAfter(atStart));
        // Just before FFA still hunt; at FFA becomes FFA
        Instant ffa = timeline.ffaMoment().orElseThrow();
        assertEquals(EventPhase.HUNT, timeline.phaseAt(ffa.minusSeconds(1)));
        assertEquals(EventPhase.FFA, timeline.phaseAt(ffa));
    }

    @Test
    void huntMatchesStartNowGuarantees() {
        Instant pastOverrideEnd = NOW.plusSeconds(100);
        ScheduleJumps.Times a = ScheduleJumps.hunt(NOW, pastOverrideEnd, BEFORE);
        ScheduleJumps.Times b = ScheduleJumps.startNow(NOW, pastOverrideEnd, BEFORE);
        assertEquals(b.start(), a.start());
        assertEquals(b.end(), a.end());
        assertEquals(b.ffaOverride(), a.ffaOverride());
    }
}
