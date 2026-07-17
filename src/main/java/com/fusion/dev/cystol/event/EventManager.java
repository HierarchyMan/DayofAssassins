package com.fusion.dev.cystol.event;

import com.fusion.dev.cystol.config.PluginConfig;
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
 */
public final class EventManager {

    private final PluginConfig config;
    private final EventRepository repository;
    private final Logger logger;

    private volatile Instant start;
    private volatile Instant end;
    private volatile Instant ffaOverride;
    private volatile EventPhase phase = EventPhase.IDLE;
    private final AtomicBoolean ffaTeleported = new AtomicBoolean(false);
    private final AtomicBoolean ceremonyDone = new AtomicBoolean(false);

    public EventManager(PluginConfig config, EventRepository repository, Logger logger) {
        this.config = config;
        this.repository = repository;
        this.logger = logger;
    }

    public void loadFromStorageAndConfig() {
        try {
            EventRepository.StoredEvent stored = repository.load();
            this.start = firstNonNull(stored.start(), config.configuredStart().orElse(null));
            this.end = firstNonNull(stored.end(), config.configuredEnd().orElse(null));
            this.ffaOverride = firstNonNull(stored.ffaOverride(), config.configuredFfaOverride().orElse(null));
            this.ffaTeleported.set(stored.ffaTeleported());
            this.ceremonyDone.set(stored.ceremonyDone());
            refreshPhase(Instant.now());
            persist();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load event state", e);
            this.start = config.configuredStart().orElse(null);
            this.end = config.configuredEnd().orElse(null);
            this.ffaOverride = config.configuredFfaOverride().orElse(null);
            refreshPhase(Instant.now());
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

    public EventPhase refreshPhase(Instant now) {
        EventPhase next = timeline().phaseAt(now);
        this.phase = next;
        return next;
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

    public void setStart(Instant instant) {
        this.start = instant;
        config.setTimeStart(instant);
        if (instant != null && ceremonyDone.get() && end != null && instant.isAfter(end)) {
            // new event schedule
        }
        // new schedule resets ceremony/ffa flags if start is in future or reconfigured
        ceremonyDone.set(false);
        ffaTeleported.set(false);
        refreshPhase(Instant.now());
        persist();
    }

    public void setEnd(Instant instant) {
        this.end = instant;
        config.setTimeEnd(instant);
        ceremonyDone.set(false);
        refreshPhase(Instant.now());
        persist();
    }

    public void setFfaOverride(Instant instant) {
        this.ffaOverride = instant;
        config.setTimeFfa(instant);
        ffaTeleported.set(false);
        refreshPhase(Instant.now());
        persist();
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

    public boolean killsCountAt(Instant now) {
        return timeline().killsCountAt(now);
    }

    public void persist() {
        try {
            repository.save(new EventRepository.StoredEvent(
                    start, end, ffaOverride, phase, ffaTeleported.get(), ceremonyDone.get()
            ));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to persist event state", e);
        }
    }

    public void persistPhaseOnly(Instant now) {
        EventPhase p = refreshPhase(Objects.requireNonNull(now));
        try {
            repository.save(new EventRepository.StoredEvent(
                    start, end, ffaOverride, p, ffaTeleported.get(), ceremonyDone.get()
            ));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to persist phase", e);
        }
    }
}
