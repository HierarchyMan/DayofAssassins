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

    /** Whole seconds until {@code end}, or 0 if unset / already past. */
    public long secondsUntilEnd(Instant now) {
        Objects.requireNonNull(now, "now");
        if (end == null) {
            return 0L;
        }
        return Math.max(0L, end.getEpochSecond() - now.getEpochSecond());
    }

    /**
     * Instant when the current phase ends (next boundary), if known.
     * <ul>
     *   <li>COUNTDOWN → start</li>
     *   <li>HUNT → FFA moment (or end if FFA collapses to end)</li>
     *   <li>FFA → end</li>
     *   <li>IDLE / ENDED → empty</li>
     * </ul>
     */
    public Optional<Instant> nextPhaseBoundary(Instant now) {
        Objects.requireNonNull(now, "now");
        EventPhase phase = phaseAt(now);
        return switch (phase) {
            case COUNTDOWN -> Optional.ofNullable(start);
            case HUNT -> {
                Optional<Instant> ffa = ffaMoment();
                if (ffa.isPresent()) {
                    yield ffa;
                }
                yield Optional.ofNullable(end);
            }
            case FFA -> Optional.ofNullable(end);
            case IDLE, ENDED, PAUSED -> Optional.empty();
        };
    }

    /**
     * Whole seconds until the next phase change (not total time until event end).
     * Use this for HUD “countdown” copy so Hunt shows time-to-FFA, FFA shows time-to-end, etc.
     */
    public long secondsUntilNextPhase(Instant now) {
        Objects.requireNonNull(now, "now");
        return nextPhaseBoundary(now)
                .map(b -> Math.max(0L, b.getEpochSecond() - now.getEpochSecond()))
                .orElse(0L);
    }

    /**
     * Fill progress for the <em>current</em> phase segment (0 at segment start → 1 at next boundary).
     * Legacy announce-lead countdown; prefer {@link #bossBarProgress} with stored anchors.
     */
    public double phaseFillProgress(Instant now, long announceLeadSeconds) {
        Objects.requireNonNull(now, "now");
        EventPhase phase = phaseAt(now);
        return switch (phase) {
            case COUNTDOWN -> countdownFillProgress(now, announceLeadSeconds);
            case HUNT -> {
                if (start == null) {
                    yield 0.0;
                }
                Instant boundary = ffaMoment().orElse(end);
                if (boundary == null) {
                    yield 0.0;
                }
                yield segmentDrain(now, start, boundary);
            }
            case FFA -> {
                Instant from = ffaMoment().orElse(start);
                if (from == null || end == null) {
                    yield 0.0;
                }
                yield segmentDrain(now, from, end);
            }
            case ENDED -> 1.0;
            case IDLE, PAUSED -> 0.0;
        };
    }

    /**
     * Bossbar progress from stored segment anchors.
     * <ul>
     *   <li>COUNTDOWN: fill 0→1 from {@code countdownAnchor} → start</li>
     *   <li>HUNT: drain 1→0 from {@code huntEntered} → FFA moment</li>
     *   <li>FFA: drain 1→0 from {@code ffaEntered} → end</li>
     * </ul>
     * Missing anchors fall back to schedule bounds ({@code start} / FFA / end).
     */
    public double bossBarProgress(
            Instant now,
            Instant countdownAnchor,
            Instant huntEntered,
            Instant ffaEntered
    ) {
        Objects.requireNonNull(now, "now");
        EventPhase phase = phaseAt(now);
        return switch (phase) {
            case COUNTDOWN -> {
                if (start == null) {
                    yield 0.0;
                }
                Instant from = countdownAnchor != null ? countdownAnchor : now;
                yield segmentFill(now, from, start);
            }
            case HUNT -> {
                Instant boundary = ffaMoment().orElse(end);
                if (boundary == null) {
                    yield 0.0;
                }
                Instant from = huntEntered != null ? huntEntered : (start != null ? start : now);
                yield segmentDrain(now, from, boundary);
            }
            case FFA -> {
                if (end == null) {
                    yield 0.0;
                }
                Instant from = ffaEntered != null
                        ? ffaEntered
                        : ffaMoment().orElse(start != null ? start : now);
                yield segmentDrain(now, from, end);
            }
            case ENDED -> 0.0;
            case IDLE, PAUSED -> 0.0;
        };
    }

    public boolean killsCountAt(Instant now) {
        EventPhase p = phaseAt(now);
        return p == EventPhase.HUNT || p == EventPhase.FFA;
    }

    /**
     * Cosmetic pre-hunt grace only: last {@code graceSeconds} of {@link EventPhase#COUNTDOWN}.
     * Does <strong>not</strong> change {@link #phaseAt}, kill windows, or any side-effect schedule.
     *
     * @param enabled       master toggle
     * @param graceSeconds  window length; {@code <= 0} means off
     */
    public boolean inGraceWindow(Instant now, boolean enabled, long graceSeconds) {
        Objects.requireNonNull(now, "now");
        if (!enabled || graceSeconds <= 0L || start == null) {
            return false;
        }
        if (phaseAt(now) != EventPhase.COUNTDOWN) {
            return false;
        }
        long untilStart = secondsUntilNextPhase(now);
        return untilStart > 0L && untilStart <= graceSeconds;
    }

    /**
     * Grace bar fill: 0 at grace open (start − graceSeconds), 1 at hunt start.
     * When outside the window, returns 0 before open and 1 at/after start.
     */
    public double graceFillProgress(Instant now, long graceSeconds) {
        Objects.requireNonNull(now, "now");
        if (start == null || graceSeconds <= 0L) {
            return 0.0;
        }
        Instant graceOpen = start.minusSeconds(graceSeconds);
        return segmentFill(now, graceOpen, start);
    }

    private static double segmentFill(Instant now, Instant from, Instant to) {
        if (from == null || to == null) {
            return 0.0;
        }
        if (now.isBefore(from)) {
            return 0.0;
        }
        if (!now.isBefore(to)) {
            return 1.0;
        }
        long total = to.getEpochSecond() - from.getEpochSecond();
        if (total <= 0) {
            return 1.0;
        }
        long elapsed = now.getEpochSecond() - from.getEpochSecond();
        return clamp01((double) elapsed / (double) total);
    }

    /** 1 at segment open → 0 at boundary (time remaining / total). */
    private static double segmentDrain(Instant now, Instant from, Instant to) {
        if (from == null || to == null) {
            return 0.0;
        }
        if (now.isBefore(from)) {
            return 1.0;
        }
        if (!now.isBefore(to)) {
            return 0.0;
        }
        long total = to.getEpochSecond() - from.getEpochSecond();
        if (total <= 0) {
            return 0.0;
        }
        long remaining = to.getEpochSecond() - now.getEpochSecond();
        return clamp01((double) remaining / (double) total);
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
