package com.fusion.dev.cystol.event;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.arena.FfaSpawnService;
import com.fusion.dev.cystol.ceremony.EndCeremonyService;
import com.fusion.dev.cystol.compass.CompassListener;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.display.TabDisplayService;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.util.TimeUtil;
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
import java.util.Set;
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
    private final Logger logger;

    private BukkitTask tickTask;
    private final Set<Long> firedAnnounceEpochs = new HashSet<>();
    private EventPhase lastPhase = EventPhase.IDLE;
    private BukkitTask outsideActionbarTask;

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
        this.logger = logger;
    }

    public void start() {
        stop();
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
    }

    private void tick() {
        Instant now = Instant.now();
        EventPhase phase = eventManager.refreshPhase(now);
        eventManager.persist();

        if (phase != lastPhase) {
            onPhaseChange(lastPhase, phase, now);
            lastPhase = phase;
        }

        if (phase == EventPhase.COUNTDOWN || phase == EventPhase.HUNT || phase == EventPhase.FFA) {
            tabDisplayService.update(now);
        } else {
            tabDisplayService.clear();
        }

        if (phase == EventPhase.HUNT || phase == EventPhase.COUNTDOWN) {
            maybeAnnounceFfa(now);
        }

        if (phase == EventPhase.FFA && !eventManager.isFfaTeleported()) {
            runFfaTeleport();
        }

        if (phase == EventPhase.ENDED && !eventManager.isCeremonyDone()) {
            ceremonyService.runCeremony();
            eventManager.markCeremonyDone();
            tabDisplayService.clear();
        }
    }

    private void onPhaseChange(EventPhase from, EventPhase to, Instant now) {
        logger.info("Event phase: " + from + " -> " + to);
        if (to == EventPhase.HUNT && from == EventPhase.COUNTDOWN) {
            compassListener.giveToAllOnline();
        }
        if (to == EventPhase.HUNT && from == EventPhase.IDLE) {
            compassListener.giveToAllOnline();
        }
    }

    private void maybeAnnounceFfa(Instant now) {
        var ffaOpt = eventManager.ffaMoment();
        if (ffaOpt.isEmpty()) {
            return;
        }
        Instant ffa = ffaOpt.get();
        if (!now.isBefore(ffa)) {
            return;
        }
        long lead = config.announceLeadSeconds();
        long interval = Math.max(1, config.announceIntervalSeconds());
        Instant windowStart = ffa.minusSeconds(lead);
        if (now.isBefore(windowStart)) {
            return;
        }
        long secsToFfa = ffa.getEpochSecond() - now.getEpochSecond();
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

    private void runFfaTeleport() {
        eventManager.markFfaTeleported();
        List<Player> eligible = ffaSpawnService.eligiblePlayers();
        ffaSpawnService.teleportPlayers(eligible);
        Title title = Title.title(
                lang.msg("ffa.start-title"),
                lang.msg("ffa.start-subtitle"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
        );
        for (Player p : eligible) {
            p.showTitle(title);
            effects.play(p, EffectService.EffectKey.FFA_TELEPORT);
        }
        startOutsideActionbar();
        logger.info("FFA teleport complete for " + eligible.size() + " players");
    }

    private void startOutsideActionbar() {
        if (outsideActionbarTask != null) {
            outsideActionbarTask.cancel();
        }
        int seconds = (int) Math.max(1, config.outsideActionbarSeconds());
        final int[] left = {seconds};
        CuboidBounds cuboid = config.arenaCuboid();
        String worldName = config.arenaWorld();
        outsideActionbarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0]-- <= 0 || eventManager.phase() != EventPhase.FFA) {
                if (outsideActionbarTask != null) {
                    outsideActionbarTask.cancel();
                    outsideActionbarTask = null;
                }
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld() == null || !p.getWorld().getName().equals(worldName)
                        || !cuboid.contains(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ())) {
                    p.sendActionBar(lang.msg("ffa.outside-actionbar"));
                }
            }
        }, 0L, 20L);
    }
}
