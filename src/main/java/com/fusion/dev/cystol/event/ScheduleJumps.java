package com.fusion.dev.cystol.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Pure time-jump math for admin ops. Phase stays clock-derived; these produce
 * (start, end, ffaOverride) triples so {@link EventTimeline#phaseAt} matches intent.
 */
public final class ScheduleJumps {

    public record Times(Instant start, Instant end, Instant ffaOverride) {
    }

    private ScheduleJumps() {
    }

    /**
     * Enter hunt: start just in the past, no FFA override, FFA moment strictly after {@code now}.
     */
    public static Times startNow(Instant now, Instant currentEnd, long ffaBeforeEndSeconds) {
        Objects.requireNonNull(now, "now");
        long before = Math.max(0L, ffaBeforeEndSeconds);
        Instant start = now.minusSeconds(1);
        Instant end = currentEnd;
        long minLead = Math.max(60L, before + 600L);
        if (end == null || !end.isAfter(start)) {
            end = start.plusSeconds(minLead);
        }
        // Clear override; ensure derived FFA = end − before is still in the future
        Instant ffa = end.minusSeconds(before);
        if (!ffa.isAfter(now)) {
            end = now.plusSeconds(before + 600L);
        }
        return new Times(start, end, null);
    }

    /**
     * Enter FFA: start in the past, FFA override = now, end strictly after now.
     */
    public static Times ffaNow(Instant now, Instant currentStart, Instant currentEnd, long ffaBeforeEndSeconds) {
        Objects.requireNonNull(now, "now");
        long before = Math.max(60L, ffaBeforeEndSeconds);
        Instant start = currentStart;
        if (start == null || start.isAfter(now)) {
            start = now.minusSeconds(1);
        }
        Instant end = currentEnd;
        if (end == null || !end.isAfter(now)) {
            end = now.plusSeconds(before);
        }
        return new Times(start, end, now);
    }

    /**
     * Enter ended: start in the past, end = now (phase ENDED at {@code now}).
     */
    public static Times endNow(Instant now, Instant currentStart) {
        Objects.requireNonNull(now, "now");
        Instant start = currentStart;
        if (start == null || start.isAfter(now)) {
            start = now.minusSeconds(1);
        }
        return new Times(start, now, null);
    }

    /**
     * Countdown: start in the future by {@code leadSeconds}.
     * Clears FFA override intent (null) and ensures a real HUNT window:
     * derived FFA ({@code end − before}) is strictly after {@code start}.
     */
    public static Times countdown(Instant now, Instant currentEnd, long leadSeconds, long ffaBeforeEndSeconds) {
        Objects.requireNonNull(now, "now");
        long lead = Math.max(1L, leadSeconds);
        long before = Math.max(0L, ffaBeforeEndSeconds);
        Instant start = now.plusSeconds(lead);
        Instant end = currentEnd;
        if (end == null || !end.isAfter(start)) {
            end = start.plusSeconds(Math.max(3600L, before + 600L));
        }
        // Same footgun as startNow: end can be after start while end−before ≤ start → FFA at start
        Instant ffa = end.minusSeconds(before);
        if (!ffa.isAfter(start)) {
            end = start.plusSeconds(before + 600L);
        }
        return new Times(start, end, null);
    }

    /**
     * Hunt via explicit phase command: same guarantees as {@link #startNow}.
     */
    public static Times hunt(Instant now, Instant currentEnd, long ffaBeforeEndSeconds) {
        return startNow(now, currentEnd, ffaBeforeEndSeconds);
    }
}
