package com.fusion.dev.cystol.event;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.arena.FfaSpawnService;
import com.fusion.dev.cystol.ceremony.EndCeremonyService;
import com.fusion.dev.cystol.compass.CompassListener;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.display.TabDisplayService;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.teleport.SpawnHuntRtpService;
import com.fusion.dev.cystol.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class EventScheduler {

    private final JavaPlugin plugin;
    private final EventManager eventManager;
    private final PluginConfig config;
    private final Lang lang;
    private final FfaSpawnService ffaSpawnService;
    private final CompassListener compassListener;
    private final EndCeremonyService ceremonyService;
    private final TabDisplayService tabDisplayService;
    private final EffectService effects;
    private final SpawnHuntRtpService spawnHuntRtpService;
    private final Logger logger;

    private BukkitTask tickTask;
    private final Set<Long> firedAnnounceEpochs = new HashSet<>();
    private final Set<Integer> firedFinalCountdownSeconds = new HashSet<>();
    private Long announceScheduleKey;
    /** Start-epoch key for cosmetic grace enter (one-shot title/sound per start schedule). */
    private Long graceScheduleKey;
    private boolean graceEnterFired;
    private EventPhase lastPhase = EventPhase.IDLE;
    private BukkitTask outsideActionbarTask;
    private Component outsideActionbarMessage;
    private final AtomicBoolean ffaTpInProgress = new AtomicBoolean(false);
    private BukkitTask ffaBatchTask;

    public EventScheduler(
            JavaPlugin plugin,
            EventManager eventManager,
            PluginConfig config,
            Lang lang,
            FfaSpawnService ffaSpawnService,
            CompassListener compassListener,
            EndCeremonyService ceremonyService,
            TabDisplayService tabDisplayService,
            EffectService effects,
            SpawnHuntRtpService spawnHuntRtpService,
            Logger logger
    ) {
        this.plugin = plugin;
        this.eventManager = eventManager;
        this.config = config;
        this.lang = lang;
        this.ffaSpawnService = ffaSpawnService;
        this.compassListener = compassListener;
        this.ceremonyService = ceremonyService;
        this.tabDisplayService = tabDisplayService;
        this.effects = effects;
        this.spawnHuntRtpService = spawnHuntRtpService;
        this.logger = logger;
    }

    public void start() {
        stop();
        firedAnnounceEpochs.clear();
        firedFinalCountdownSeconds.clear();
        announceScheduleKey = null;
        graceScheduleKey = null;
        graceEnterFired = false;
        Instant now = Instant.now();
        EventPhase current = eventManager.refreshPhase(now);
        // Restart after a finished event: freeze in pause (same as post-ceremony)
        if (current == EventPhase.ENDED && eventManager.isCeremonyDone()) {
            eventManager.pause();
            current = EventPhase.PAUSED;
        }
        lastPhase = current;
        // Recovery: if already mid-event after restart, ensure online players get compasses
        if (current == EventPhase.HUNT || current == EventPhase.FFA) {
            compassListener.giveToAllOnline();
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (outsideActionbarTask != null) {
            outsideActionbarTask.cancel();
            outsideActionbarTask = null;
        }
        outsideActionbarMessage = null;
        cancelFfaBatch();
        ffaTpInProgress.set(false);
        if (spawnHuntRtpService != null) {
            spawnHuntRtpService.shutdown();
        }
    }

    private void cancelFfaBatch() {
        if (ffaBatchTask != null) {
            ffaBatchTask.cancel();
            ffaBatchTask = null;
        }
    }

    private void tick() {
        Instant now = Instant.now();
        EventPhase phase = eventManager.refreshPhase(now);

        if (phase != lastPhase) {
            onPhaseChange(lastPhase, phase, now);
            lastPhase = phase;
            eventManager.persist();
        }

        // Frozen schedule: no announces, FFA TP, ceremony. UI already cleared on enter PAUSED.
        if (phase == EventPhase.PAUSED || eventManager.isPaused()) {
            return;
        }

        if (phase == EventPhase.COUNTDOWN || phase == EventPhase.HUNT || phase == EventPhase.FFA) {
            tabDisplayService.update(now);
        }
        // clear only on phase leave (onPhaseChange) — not every idle second

        if (phase == EventPhase.COUNTDOWN) {
            maybeGraceEnter(now);
        }

        if (phase == EventPhase.HUNT || phase == EventPhase.COUNTDOWN) {
            maybeFinalCountdown(now);
            maybeAnnounceFfa(now);
        }

        if (phase == EventPhase.FFA && !eventManager.isFfaTeleported() && !ffaTpInProgress.get()) {
            String err = runFfaTeleport();
            if (err != null) {
                logger.warning("FFA teleport deferred: " + err);
            }
        }

        if (phase == EventPhase.ENDED && !eventManager.isCeremonyDone()) {
            runCeremonyOnce();
        }
    }

    /** Immediate bossbar/scoreboard push after unpause or schedule force-jump. */
    public void refreshDisplayNow() {
        Instant now = Instant.now();
        EventPhase phase = eventManager.refreshPhase(now);
        if (phase == EventPhase.COUNTDOWN || phase == EventPhase.HUNT || phase == EventPhase.FFA) {
            tabDisplayService.forceRefresh(now);
        } else {
            tabDisplayService.clear();
        }
    }

    /**
     * Admin force: re-run spawn-cuboid hunt RTP mass dump if currently live HUNT.
     * @return null on success, otherwise a lang-key suffix reason
     */
    public String forceSpawnHuntRtp() {
        if (spawnHuntRtpService == null) {
            return "no-bridge";
        }
        return spawnHuntRtpService.forceKickoff();
    }

    public int lastForceSpawnRtpCount() {
        return spawnHuntRtpService == null ? 0 : spawnHuntRtpService.lastForceCount();
    }

    /**
     * Admin force: re-run FFA mass TP if currently in FFA phase.
     * @return null on success, otherwise a lang-key suffix reason
     * ({@code not-ffa}, {@code in-progress}, {@code no-spawns})
     */
    public String forceFfaTeleport() {
        EventPhase phase = eventManager.refreshPhase(Instant.now());
        if (phase != EventPhase.FFA) {
            return "not-ffa";
        }
        if (ffaTpInProgress.get()) {
            return "in-progress";
        }
        eventManager.clearFfaTeleported();
        return runFfaTeleport();
    }

    /**
     * Admin force: re-run end ceremony if the live clock is past end
     * (works even when already auto-paused after a prior ceremony).
     * @return null on success, otherwise {@code not-ended}
     */
    public String forceCeremony() {
        Instant now = Instant.now();
        if (eventManager.livePhaseAt(now) != EventPhase.ENDED) {
            return "not-ended";
        }
        eventManager.clearCeremonyDone();
        runCeremonyOnce();
        return null;
    }

    private void runCeremonyOnce() {
        ceremonyService.runCeremony();
        tabDisplayService.clear();
        // Single persist: ceremony_done + paused (survives restart without re-firing)
        eventManager.markCeremonyDoneAndPause();
        lastPhase = EventPhase.PAUSED;
        logger.info("End ceremony complete — event paused until host unpauses");
    }

    private void onPhaseChange(EventPhase from, EventPhase to, Instant now) {
        logger.info("Event phase: " + from + " -> " + to);
        // Any transition into an active scoring phase should mass-give compasses
        if ((to == EventPhase.HUNT || to == EventPhase.FFA)
                && from != EventPhase.HUNT && from != EventPhase.FFA) {
            compassListener.giveToAllOnline();
        }
        // Leave scoring (incl. pause): strip every Assassin's Compass (silent)
        if ((from == EventPhase.HUNT || from == EventPhase.FFA)
                && to != EventPhase.HUNT && to != EventPhase.FFA) {
            compassListener.stripAllOnlineSilent();
        }
        // Hunt kickoff toast + one-shot spawn-cuboid BetterRTP (not on PAUSED recovery / restart)
        if (to == EventPhase.HUNT && from != EventPhase.HUNT && from != EventPhase.PAUSED) {
            broadcastHuntStart();
            if (spawnHuntRtpService != null) {
                String rtpErr = spawnHuntRtpService.runKickoffIfNeeded();
                if (rtpErr != null && !"already-done".equals(rtpErr) && !"disabled".equals(rtpErr)) {
                    logger.warning("Hunt spawn RTP kickoff: " + rtpErr);
                }
            }
        }
        // Entering live display phases — push bossbar/scoreboard immediately (esp. unpause)
        if (to == EventPhase.COUNTDOWN || to == EventPhase.HUNT || to == EventPhase.FFA) {
            tabDisplayService.forceRefresh(now);
        }
        // Leaving live display — clear once (do not clear every paused tick)
        if (to == EventPhase.PAUSED || to == EventPhase.IDLE || to == EventPhase.ENDED) {
            tabDisplayService.clear();
        }
        if (to == EventPhase.COUNTDOWN || to == EventPhase.IDLE || to == EventPhase.PAUSED) {
            firedAnnounceEpochs.clear();
            firedFinalCountdownSeconds.clear();
            announceScheduleKey = null;
        }
        // Leaving countdown (or any non-countdown): allow grace toast again if start is rewound
        if (to != EventPhase.COUNTDOWN) {
            graceEnterFired = false;
        }
    }

    /**
     * Cosmetic only: once per start schedule when the last-N-seconds grace window opens.
     * Never advances phase or side effects.
     */
    private void maybeGraceEnter(Instant now) {
        if (!config.graceEnabled()) {
            return;
        }
        long graceSecs = config.graceSeconds();
        if (graceSecs <= 0L) {
            return;
        }
        EventTimeline timeline = eventManager.timeline();
        Optional<Instant> startOpt = timeline.start();
        if (startOpt.isEmpty()) {
            return;
        }
        Instant start = startOpt.get();
        long startEpoch = start.getEpochSecond();
        if (graceScheduleKey == null || graceScheduleKey != startEpoch) {
            graceScheduleKey = startEpoch;
            graceEnterFired = false;
        }
        if (!timeline.inGraceWindow(now, true, graceSecs)) {
            // Before window: keep unfired. After start: phase leaves COUNTDOWN and flag resets above.
            return;
        }
        if (graceEnterFired) {
            return;
        }
        graceEnterFired = true;
        broadcastGraceStart(timeline.secondsUntilNextPhase(now));
    }

    private void broadcastGraceStart(long secondsUntilHunt) {
        String countdown = TimeUtil.formatCountdown(Math.max(0L, secondsUntilHunt));
        Map<String, String> ph = Map.of("countdown", countdown);
        for (Player p : Bukkit.getOnlinePlayers()) {
            effects.showTitle(
                    p,
                    EffectService.EffectKey.GRACE_START,
                    lang.msg("grace.start-title", ph),
                    lang.msg("grace.start-subtitle", ph)
            );
            effects.play(p, EffectService.EffectKey.GRACE_START);
        }
        logger.info("Cosmetic grace window opened — hunt in " + countdown);
    }

    private void broadcastHuntStart() {
        Title title = Title.title(
                lang.msg("hunt.start-title"),
                lang.msg("hunt.start-subtitle"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            effects.play(p, EffectService.EffectKey.HUNT_START);
        }
    }

    private void syncAnnounceSchedule(Instant ffa) {
        long ffaEpoch = ffa.getEpochSecond();
        if (announceScheduleKey == null || announceScheduleKey != ffaEpoch) {
            firedAnnounceEpochs.clear();
            firedFinalCountdownSeconds.clear();
            announceScheduleKey = ffaEpoch;
        }
    }

    /**
     * Last N seconds before FFA moment: title + sound for each remaining second (default 5…1).
     * Runs before ring TP so players get a clear beat, then {@link #runFfaTeleport} at FFA phase.
     */
    private void maybeFinalCountdown(Instant now) {
        if (!config.ffaFinalCountdownEnabled()) {
            return;
        }
        int from = config.ffaFinalCountdownFromSeconds();
        if (from <= 0) {
            return;
        }
        var ffaOpt = eventManager.ffaMoment();
        if (ffaOpt.isEmpty()) {
            return;
        }
        Instant ffa = ffaOpt.get();
        syncAnnounceSchedule(ffa);
        if (!now.isBefore(ffa)) {
            return;
        }
        long secsToFfa = ffa.getEpochSecond() - now.getEpochSecond();
        OptionalInt tick = FfaFinalCountdown.secondToAnnounce(secsToFfa, from, firedFinalCountdownSeconds);
        if (tick.isEmpty()) {
            return;
        }
        int remaining = tick.getAsInt();
        firedFinalCountdownSeconds.add(remaining);

        Map<String, String> ph = Map.of("seconds", String.valueOf(remaining));
        Title.Times times = Title.Times.times(
                Duration.ofMillis(config.ffaFinalCountdownFadeInMs()),
                Duration.ofMillis(config.ffaFinalCountdownStayMs()),
                Duration.ofMillis(config.ffaFinalCountdownFadeOutMs())
        );
        Title title = Title.title(
                lang.msg("ffa.final-countdown-title", ph),
                lang.msg("ffa.final-countdown-subtitle", ph),
                times
        );
        float pitch = FfaFinalCountdown.pitchForRemaining(
                config.ffaFinalCountdownPitchBase(),
                config.ffaFinalCountdownPitchStep(),
                from,
                remaining
        );

        for (Player p : finalCountdownAudience()) {
            p.showTitle(title);
            effects.play(p, EffectService.EffectKey.FFA_FINAL_COUNTDOWN, p.getLocation(), pitch);
        }
    }

    private List<Player> finalCountdownAudience() {
        if ("eligible".equals(config.ffaFinalCountdownAudience())) {
            return ffaSpawnService.eligiblePlayers();
        }
        return List.copyOf(Bukkit.getOnlinePlayers());
    }

    private void maybeAnnounceFfa(Instant now) {
        var ffaOpt = eventManager.ffaMoment();
        if (ffaOpt.isEmpty()) {
            return;
        }
        Instant ffa = ffaOpt.get();
        syncAnnounceSchedule(ffa);
        if (!now.isBefore(ffa)) {
            return;
        }
        long secsToFfa = ffa.getEpochSecond() - now.getEpochSecond();
        // Avoid double titles with the short 5…1 beat
        if (config.ffaFinalCountdownEnabled()) {
            int from = config.ffaFinalCountdownFromSeconds();
            if (from > 0 && secsToFfa > 0 && secsToFfa <= from) {
                return;
            }
        }
        long lead = config.announceLeadSeconds();
        long interval = Math.max(1, config.announceIntervalSeconds());
        Instant windowStart = ffa.minusSeconds(lead);
        if (now.isBefore(windowStart)) {
            return;
        }
        // Fire at interval boundaries: when remaining is multiple of interval, or first enter window
        long slotsFromFfa = secsToFfa / interval;
        long boundaryEpoch = ffa.getEpochSecond() - slotsFromFfa * interval;
        // also fire when just entered window
        if (secsToFfa == lead || secsToFfa % interval == 0 || Math.abs(now.getEpochSecond() - boundaryEpoch) <= 1) {
            long key = boundaryEpoch;
            if (firedAnnounceEpochs.add(key)) {
                String countdown = TimeUtil.formatCountdown(secsToFfa);
                Title title = Title.title(
                        lang.msg("ffa.announce-title", Map.of("countdown", countdown)),
                        lang.msg("ffa.announce-subtitle", Map.of("countdown", countdown)),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
                );
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                    effects.play(p, EffectService.EffectKey.FFA_ANNOUNCE);
                }
            }
        }
    }

    /**
     * @return null on success (including zero eligible players), else reason key suffix
     */
    private String runFfaTeleport() {
        if (!ffaTpInProgress.compareAndSet(false, true)) {
            return "in-progress";
        }
        boolean marked = false;
        try {
            List<Player> eligible = ffaSpawnService.eligiblePlayers();
            // Nobody to TP: one-shot complete (do not retry every tick forever)
            if (eligible.isEmpty()) {
                eventManager.markFfaTeleported();
                marked = true;
                ffaTpInProgress.set(false);
                startOutsideActionbar();
                logger.info("FFA teleport complete for 0 players (none eligible)");
                return null;
            }
            // Preflight: world loaded + spawn points — do NOT mark yet if this fails
            if (!ffaSpawnService.canBuildSpawns(eligible.size())) {
                ffaTpInProgress.set(false);
                logger.warning("FFA teleport blocked: no spawn locations (arena world / cuboid)");
                return "no-spawns";
            }

            Title title = Title.title(
                    lang.msg("ffa.start-title"),
                    lang.msg("ffa.start-subtitle"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
            );
            cancelFfaBatch();
            // Mark only after preflight success so unloaded world can still be retried next tick
            eventManager.markFfaTeleported();
            marked = true;
            ffaBatchTask = ffaSpawnService.teleportPlayersBatched(
                    plugin,
                    eligible,
                    FfaSpawnService.DEFAULT_BATCH_SIZE,
                    p -> {
                        p.showTitle(title);
                        effects.play(p, EffectService.EffectKey.FFA_TELEPORT);
                    },
                    () -> {
                        ffaBatchTask = null;
                        ffaTpInProgress.set(false);
                        startOutsideActionbar();
                        logger.info("FFA teleport complete for " + eligible.size() + " players");
                    }
            );
            // Non-empty players + null task = spawn list empty (race) — do not keep one-shot mark
            if (ffaBatchTask == null) {
                eventManager.clearFfaTeleported();
                ffaTpInProgress.set(false);
                return "no-spawns";
            }
            return null;
        } catch (RuntimeException e) {
            ffaTpInProgress.set(false);
            ffaBatchTask = null;
            if (marked) {
                eventManager.clearFfaTeleported();
            }
            logger.warning("FFA teleport failed: " + e.getMessage());
            return "failed";
        }
    }

    private void startOutsideActionbar() {
        if (outsideActionbarTask != null) {
            outsideActionbarTask.cancel();
        }
        int seconds = (int) Math.max(1, config.outsideActionbarSeconds());
        final int[] left = {seconds};
        CuboidBounds cuboid = config.arenaCuboid();
        String worldName = config.arenaWorld();
        // Build message once for the nudge window.
        outsideActionbarMessage = lang.msg("ffa.outside-actionbar");
        outsideActionbarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0]-- <= 0 || eventManager.phase() != EventPhase.FFA) {
                if (outsideActionbarTask != null) {
                    outsideActionbarTask.cancel();
                    outsideActionbarTask = null;
                }
                outsideActionbarMessage = null;
                return;
            }
            Component msg = outsideActionbarMessage;
            if (msg == null) {
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld() == null || !p.getWorld().getName().equals(worldName)
                        || !cuboid.contains(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ())) {
                    p.sendActionBar(msg);
                }
            }
        }, 0L, 20L);
    }
}
