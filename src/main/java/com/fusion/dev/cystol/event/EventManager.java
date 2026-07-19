package com.fusion.dev.cystol.event;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.storage.EventRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory event times + phase with SQLite flush.
 * Schedule edits set {@link #paused()} so the clock does not advance side effects until {@link #unpause()}.
 *
 * <p>Bossbar segment anchors (persisted):
 * <ul>
 *   <li>{@code countdownAnchor} — wall clock when start was first armed</li>
 *   <li>{@code huntEntered} — wall clock when live phase first became HUNT</li>
 *   <li>{@code ffaEntered} — wall clock when live phase first entered FFA</li>
 * </ul>
 *
 * <p>On first HUNT enter of a schedule arm, live kill scores are wiped (past games stay).
 * On event end, live scores are archived into chronological past games (see scheduler).
 */
public final class EventManager {

    private final PluginConfig config;
    private final EventRepository repository;
    private final Logger logger;
    /** Optional: wipe live kills the first time this arm enters HUNT. */
    private volatile KillService killService;

    private volatile Instant start;
    private volatile Instant end;
    private volatile Instant ffaOverride;
    private volatile EventPhase phase = EventPhase.IDLE;
    private final AtomicBoolean ffaTeleported = new AtomicBoolean(false);
    private final AtomicBoolean ceremonyDone = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    /** One-shot: hunt-kickoff spawn-cuboid BetterRTP mass dump completed for this schedule. */
    private final AtomicBoolean spawnRtpDone = new AtomicBoolean(false);
    /** Live scores already copied into past_games for this schedule arm. */
    private final AtomicBoolean scoresArchived = new AtomicBoolean(false);
    /**
     * When true, the next first HUNT enter wipes live kills.
     * Set when a schedule arm is (re)armed; not set on mid-event restart recovery.
     */
    private final AtomicBoolean wipeKillsOnNextHuntEnter = new AtomicBoolean(false);

    /** Wall clock when start was first set for this schedule arm. */
    private volatile Instant countdownAnchor;
    /** Wall clock when live phase first entered HUNT this arm. */
    private volatile Instant huntEntered;
    /** Wall clock when live phase first entered FFA this arm. */
    private volatile Instant ffaEntered;

    public EventManager(PluginConfig config, EventRepository repository, Logger logger) {
        this.config = config;
        this.repository = repository;
        this.logger = logger;
    }

    /**
     * Wire kill wipe-on-hunt-start. Safe to call after construction (plugin boot order).
     */
    public void bindKillService(KillService killService) {
        this.killService = killService;
    }

    public void loadFromStorageAndConfig() {
        Instant now = Instant.now();
        try {
            EventRepository.StoredEvent stored = repository.load();
            this.start = firstNonNull(stored.start(), config.configuredStart().orElse(null));
            this.end = firstNonNull(stored.end(), config.configuredEnd().orElse(null));
            this.ffaOverride = firstNonNull(stored.ffaOverride(), config.configuredFfaOverride().orElse(null));
            this.ffaTeleported.set(stored.ffaTeleported());
            this.ceremonyDone.set(stored.ceremonyDone());
            this.paused.set(stored.paused());
            this.spawnRtpDone.set(stored.spawnRtpDone());
            this.scoresArchived.set(stored.scoresArchived());
            this.countdownAnchor = stored.countdownAnchor();
            this.huntEntered = stored.huntEntered();
            this.ffaEntered = stored.ffaEntered();
            refreshPhase(now);
            recoverAnchors(now);
            persist();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load event state", e);
            this.start = config.configuredStart().orElse(null);
            this.end = config.configuredEnd().orElse(null);
            this.ffaOverride = config.configuredFfaOverride().orElse(null);
            refreshPhase(now);
            recoverAnchors(now);
        }
    }

    private static Instant firstNonNull(Instant a, Instant b) {
        return a != null ? a : b;
    }

    public EventTimeline timeline() {
        return new EventTimeline(start, end, ffaOverride, config.ffaBeforeEndSeconds());
    }

    public EventPhase phase() {
        return phase;
    }

    public boolean isPaused() {
        return paused.get();
    }

    public Optional<Instant> countdownAnchor() {
        return Optional.ofNullable(countdownAnchor);
    }

    public Optional<Instant> huntEntered() {
        return Optional.ofNullable(huntEntered);
    }

    public Optional<Instant> ffaEntered() {
        return Optional.ofNullable(ffaEntered);
    }

    /**
     * Bossbar 0–1 progress for the live timeline phase (ignores PAUSED display).
     */
    public double bossBarProgress(Instant now) {
        Instant t = now == null ? Instant.now() : now;
        return timeline().bossBarProgress(t, countdownAnchor, huntEntered, ffaEntered);
    }

    /**
     * Clock-derived phase ignoring pause (what would run if unpaused now).
     */
    public EventPhase livePhaseAt(Instant now) {
        return timeline().phaseAt(now);
    }

    /**
     * While paused, always {@link EventPhase#PAUSED}. Otherwise timeline phase.
     * Stamps hunt/ffa enter anchors once when live phase first hits those stages.
     */
    public EventPhase refreshPhase(Instant now) {
        Instant t = Objects.requireNonNull(now, "now");
        if (paused.get()) {
            this.phase = EventPhase.PAUSED;
            return this.phase;
        }
        EventPhase next = timeline().phaseAt(t);
        stampEnterIfNeeded(next, t);
        this.phase = next;
        return next;
    }

    /**
     * Freeze progression. Changing times always pauses so a past start cannot instantly open hunt.
     */
    public void pause() {
        if (paused.getAndSet(true)) {
            this.phase = EventPhase.PAUSED;
            persist();
            return;
        }
        this.phase = EventPhase.PAUSED;
        persist();
    }

    /**
     * Resume and snap to the live timeline phase for {@code now}.
     *
     * @return phase after unpause
     */
    public EventPhase unpause(Instant now) {
        Instant t = Objects.requireNonNull(now, "now");
        paused.set(false);
        EventPhase next = timeline().phaseAt(t);
        stampEnterIfNeeded(next, t);
        this.phase = next;
        persist();
        return next;
    }

    public void setPaused(boolean value) {
        if (value) {
            pause();
        } else {
            unpause(Instant.now());
        }
    }

    public Optional<Instant> start() {
        return Optional.ofNullable(start);
    }

    public Optional<Instant> end() {
        return Optional.ofNullable(end);
    }

    public Optional<Instant> ffaMoment() {
        return timeline().ffaMoment();
    }

    /** Absolute FFA override if set (not the derived end − offset moment). */
    public Optional<Instant> ffaOverride() {
        return Optional.ofNullable(ffaOverride);
    }

    public void setStart(Instant instant) {
        this.start = instant;
        config.setTimeStart(instant);
        ceremonyDone.set(false);
        ffaTeleported.set(false);
        spawnRtpDone.set(false);
        scoresArchived.set(false);
        // Any start arm is a new pre-hunt segment: re-anchor fill + drop live-enter stamps
        // so a second event after the first cannot keep draining from old hunt/ffa enters.
        if (instant == null) {
            clearAnchors();
            wipeKillsOnNextHuntEnter.set(false);
        } else {
            countdownAnchor = Instant.now();
            huntEntered = null;
            ffaEntered = null;
            wipeKillsOnNextHuntEnter.set(true);
        }
        pause(); // schedule edit freezes until host unpauses
    }

    public void setEnd(Instant instant) {
        this.end = instant;
        config.setTimeEnd(instant);
        ceremonyDone.set(false);
        ffaTeleported.set(false);
        spawnRtpDone.set(false);
        scoresArchived.set(false);
        pause();
    }

    public void setFfaOverride(Instant instant) {
        this.ffaOverride = instant;
        config.setTimeFfa(instant);
        ffaTeleported.set(false);
        // FFA override alone does not re-arm spawn kickoff
        pause();
    }

    /**
     * Apply a full schedule triple. Always pauses (use {@link #unpause} or force-jump helpers to go live).
     */
    public void applySchedule(ScheduleJumps.Times times) {
        Objects.requireNonNull(times, "times");
        this.start = times.start();
        this.end = times.end();
        this.ffaOverride = times.ffaOverride();
        config.setTimeStart(times.start());
        config.setTimeEnd(times.end());
        config.setTimeFfa(times.ffaOverride());
        ceremonyDone.set(false);
        ffaTeleported.set(false);
        spawnRtpDone.set(false);
        scoresArchived.set(false);
        rearmCountdownAnchor(times.start());
        huntEntered = null;
        ffaEntered = null;
        wipeKillsOnNextHuntEnter.set(times.start() != null);
        pause();
    }

    /**
     * Time-jump helpers: write schedule and immediately unpause into the live phase.
     */
    public EventPhase applyScheduleAndUnpause(ScheduleJumps.Times times, Instant now) {
        Objects.requireNonNull(times, "times");
        Instant t = Objects.requireNonNull(now, "now");
        this.start = times.start();
        this.end = times.end();
        this.ffaOverride = times.ffaOverride();
        config.setTimeStart(times.start());
        config.setTimeEnd(times.end());
        config.setTimeFfa(times.ffaOverride());
        ceremonyDone.set(false);
        ffaTeleported.set(false);
        spawnRtpDone.set(false);
        scoresArchived.set(false);
        rearmCountdownAnchor(times.start());
        huntEntered = null;
        ffaEntered = null;
        wipeKillsOnNextHuntEnter.set(times.start() != null);
        return unpause(t);
    }

    public boolean isFfaTeleported() {
        return ffaTeleported.get();
    }

    public void markFfaTeleported() {
        ffaTeleported.set(true);
        persist();
    }

    public boolean isCeremonyDone() {
        return ceremonyDone.get();
    }

    public void markCeremonyDone() {
        ceremonyDone.set(true);
        persist();
    }

    /**
     * End-of-event freeze: ceremony complete + pause in one SQLite write so a crash
     * mid-end cannot leave the clock unpaused with ceremony already marked done.
     */
    public void markCeremonyDoneAndPause() {
        ceremonyDone.set(true);
        paused.set(true);
        this.phase = EventPhase.PAUSED;
        persist();
    }

    public boolean isScoresArchived() {
        return scoresArchived.get();
    }

    public void markScoresArchived() {
        scoresArchived.set(true);
        persist();
    }

    public void clearScoresArchived() {
        scoresArchived.set(false);
        persist();
    }

    public void clearFlags() {
        ffaTeleported.set(false);
        ceremonyDone.set(false);
        spawnRtpDone.set(false);
        scoresArchived.set(false);
        persist();
    }

    public void clearFfaTeleported() {
        ffaTeleported.set(false);
        persist();
    }

    public void clearCeremonyDone() {
        ceremonyDone.set(false);
        persist();
    }

    public boolean isSpawnRtpDone() {
        return spawnRtpDone.get();
    }

    public void markSpawnRtpDone() {
        spawnRtpDone.set(true);
        persist();
    }

    public void clearSpawnRtpDone() {
        spawnRtpDone.set(false);
        persist();
    }

    public void clearSchedule() {
        this.start = null;
        this.end = null;
        this.ffaOverride = null;
        config.setTimeStart(null);
        config.setTimeEnd(null);
        config.setTimeFfa(null);
        ceremonyDone.set(false);
        ffaTeleported.set(false);
        spawnRtpDone.set(false);
        scoresArchived.set(false);
        wipeKillsOnNextHuntEnter.set(false);
        clearAnchors();
        pause();
    }

    public boolean killsCountAt(Instant now) {
        if (paused.get()) {
            return false;
        }
        return timeline().killsCountAt(now);
    }

    public void persist() {
        try {
            repository.save(snapshot());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to persist event state", e);
        }
    }

    public void persistPhaseOnly(Instant now) {
        EventPhase p = refreshPhase(Objects.requireNonNull(now));
        try {
            repository.save(new EventRepository.StoredEvent(
                    start, end, ffaOverride, p,
                    ffaTeleported.get(), ceremonyDone.get(), paused.get(), spawnRtpDone.get(),
                    scoresArchived.get(),
                    countdownAnchor, huntEntered, ffaEntered
            ));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to persist phase", e);
        }
    }

    private EventRepository.StoredEvent snapshot() {
        return new EventRepository.StoredEvent(
                start, end, ffaOverride, phase,
                ffaTeleported.get(), ceremonyDone.get(), paused.get(), spawnRtpDone.get(),
                scoresArchived.get(),
                countdownAnchor, huntEntered, ffaEntered
        );
    }

    private void rearmCountdownAnchor(Instant startTime) {
        if (startTime != null) {
            countdownAnchor = Instant.now();
        } else {
            countdownAnchor = null;
        }
    }

    private void clearAnchors() {
        countdownAnchor = null;
        huntEntered = null;
        ffaEntered = null;
    }

    /**
     * Stamp enter once per schedule arm. Safe under pause/unpause (only if null).
     * When a schedule arm was re-armed, first HUNT enter wipes live kills (past games kept).
     * Restart mid-hunt never sets {@link #wipeKillsOnNextHuntEnter}, so scores survive.
     */
    private void stampEnterIfNeeded(EventPhase live, Instant now) {
        if (live == EventPhase.HUNT && huntEntered == null) {
            huntEntered = now;
            if (wipeKillsOnNextHuntEnter.compareAndSet(true, false)) {
                wipeLiveKillsForNewHunt();
            }
        } else if (live == EventPhase.FFA && ffaEntered == null) {
            ffaEntered = now;
        }
    }

    private void wipeLiveKillsForNewHunt() {
        scoresArchived.set(false);
        KillService ks = killService;
        if (ks != null) {
            logger.info("Hunt arm opened — clearing live kill scores (past games retained)");
            ks.clearAll();
        }
    }

    /**
     * Mid-event restart / legacy DB: backfill missing anchors so the bar has a segment.
     * Does <strong>not</strong> wipe kills.
     */
    private void recoverAnchors(Instant now) {
        EventPhase live = timeline().phaseAt(now);
        if (live == EventPhase.COUNTDOWN && start != null && countdownAnchor == null) {
            countdownAnchor = now;
        }
        if (live == EventPhase.HUNT && huntEntered == null) {
            // Recover only — leave wipeKillsOnNextHuntEnter false so restart keeps scores
            huntEntered = start != null ? start : now;
        }
        if (live == EventPhase.FFA && ffaEntered == null) {
            ffaEntered = timeline().ffaMoment().orElse(start != null ? start : now);
        }
        if (!paused.get()) {
            stampEnterIfNeeded(live, now);
        }
    }
}
