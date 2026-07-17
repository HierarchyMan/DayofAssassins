package com.fusion.dev.cystol.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure timeline: FFA = end − offset unless absolute override is set and valid.
 */
public final class EventTimeline {

    private final Instant start;
    private final Instant end;
    private final Instant ffaOverride;
    private final long ffaBeforeEndSeconds;

    public EventTimeline(Instant start, Instant end, Instant ffaOverride, long ffaBeforeEndSeconds) {
        this.start = start;
        this.end = end;
        this.ffaOverride = ffaOverride;
        this.ffaBeforeEndSeconds = Math.max(0, ffaBeforeEndSeconds);
    }

    public Optional<Instant> start() {
        return Optional.ofNullable(start);
    }

    public Optional<Instant> end() {
        return Optional.ofNullable(end);
    }

    /**
     * Effective FFA moment. Prefer absolute override when set and strictly before end;
     * otherwise end − before-end offset when end is set.
     */
    public Optional<Instant> ffaMoment() {
        if (end == null) {
            if (ffaOverride != null) {
                return Optional.of(ffaOverride);
            }
            return Optional.empty();
        }
        if (ffaOverride != null && ffaOverride.isBefore(end)) {
            return Optional.of(ffaOverride);
        }
        return Optional.of(end.minusSeconds(ffaBeforeEndSeconds));
    }

    public EventPhase phaseAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (start == null) {
            return EventPhase.IDLE;
        }
        if (now.isBefore(start)) {
            return EventPhase.COUNTDOWN;
        }
        Optional<Instant> ffa = ffaMoment();
        if (end != null && !now.isBefore(end)) {
            return EventPhase.ENDED;
        }
        if (ffa.isPresent() && !now.isBefore(ffa.get())) {
            return EventPhase.FFA;
        }
        return EventPhase.HUNT;
    }

    /**
     * Pre-start bossbar fill: 0 at far past (or when no window), 1 at start.
     * Uses time from (start − announceLeadSeconds) → start when lead &gt; 0;
     * otherwise jumps from 0 to approaching 1 only in the last second window
     * is not used — if lead is 0, fill is 1 when at/after start else 0.
     * When now is before (start − lead), fill is 0; at start, fill is 1.
     */
    public double countdownFillProgress(Instant now, long announceLeadSeconds) {
        if (start == null) {
            return 0.0;
        }
        if (!now.isBefore(start)) {
            return 1.0;
        }
        long lead = Math.max(0, announceLeadSeconds);
        if (lead == 0) {
            return 0.0;
        }
        Instant windowStart = start.minusSeconds(lead);
        if (now.isBefore(windowStart)) {
            return 0.0;
        }
        long total = lead;
        long elapsed = now.getEpochSecond() - windowStart.getEpochSecond();
        if (total <= 0) {
            return 1.0;
        }
        return clamp01((double) elapsed / (double) total);
    }

    /**
     * Live event bar fills from start → end: 0 at start, 1 at end.
     */
    public double liveFillProgress(Instant now) {
        if (start == null || end == null) {
            return 0.0;
        }
        if (now.isBefore(start)) {
            return 0.0;
        }
        if (!now.isBefore(end)) {
            return 1.0;
        }
        long total = end.getEpochSecond() - start.getEpochSecond();
        if (total <= 0) {
            return 1.0;
        }
        long elapsed = now.getEpochSecond() - start.getEpochSecond();
        return clamp01((double) elapsed / (double) total);
    }

    public boolean killsCountAt(Instant now) {
        EventPhase p = phaseAt(now);
        return p == EventPhase.HUNT || p == EventPhase.FFA;
    }

    private static double clamp01(double v) {
        if (v < 0) {
            return 0;
        }
        if (v > 1) {
            return 1;
        }
        return v;
    }
}
