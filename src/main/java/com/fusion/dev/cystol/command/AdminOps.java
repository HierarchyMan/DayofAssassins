package com.fusion.dev.cystol.command;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.arena.FfaSpawnService;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventScheduler;
import com.fusion.dev.cystol.event.ScheduleJumps;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.util.TimeUtil;
import com.fusion.dev.cystol.util.VanishService;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Admin control surface: status, time-jumps, force one-shots, reload, clearkills.
 * Phase stays clock-driven; shortcuts adjust times so {@code phaseAt(now)} matches intent.
 */
public final class AdminOps {

    private static final long COUNTDOWN_LEAD_SECONDS = 300L; // 5m default for phase countdown

    private final EventManager eventManager;
    private final EventScheduler eventScheduler;
    private final KillService killService;
    private final FfaSpawnService ffaSpawnService;
    private final PluginConfig config;
    private final Lang lang;
    private final EffectService effects;
    private final VanishService vanishService;

    public AdminOps(
            EventManager eventManager,
            EventScheduler eventScheduler,
            KillService killService,
            FfaSpawnService ffaSpawnService,
            PluginConfig config,
            Lang lang,
            EffectService effects,
            VanishService vanishService
    ) {
        this.eventManager = eventManager;
        this.eventScheduler = eventScheduler;
        this.killService = killService;
        this.ffaSpawnService = ffaSpawnService;
        this.config = config;
        this.lang = lang;
        this.effects = effects;
        this.vanishService = vanishService;
    }

    public void status(CommandSender sender) {
        Instant now = Instant.now();
        EventPhase phase = eventManager.refreshPhase(now);
        Optional<Instant> start = eventManager.start();
        Optional<Instant> end = eventManager.end();
        Optional<Instant> ffa = eventManager.ffaMoment();
        String ffaSource = eventManager.ffaOverride().isPresent() ? "override" : "derived";

        long remainingSecs = 0L;
        if (phase == EventPhase.COUNTDOWN && start.isPresent()) {
            remainingSecs = Math.max(0L, start.get().getEpochSecond() - now.getEpochSecond());
        } else if ((phase == EventPhase.HUNT || phase == EventPhase.FFA) && end.isPresent()) {
            remainingSecs = Math.max(0L, end.get().getEpochSecond() - now.getEpochSecond());
        }

        Optional<DenseRanking.Entry> top = killService.topKiller();
        String topName = top.map(e -> e.name() == null ? "?" : e.name()).orElse("—");
        String topKills = top.map(e -> String.valueOf(e.kills())).orElse("0");
        int killRows = killService.snapshot().size();
        int eligible = ffaSpawnService.eligiblePlayers().size();

        CuboidBounds cuboid = config.arenaCuboid();
        boolean centerLooksSet = Math.abs(config.centerX()) > 1e-9
                || Math.abs(config.centerY() - 65.0) > 1e-9
                || Math.abs(config.centerZ()) > 1e-9
                || !"world".equals(config.arenaWorld());

        sender.sendMessage(lang.msg("admin.status.header"));
        sender.sendMessage(lang.msg("admin.status.phase", Map.of("phase", phase.name())));
        sender.sendMessage(lang.msg("admin.status.start", Map.of(
                "time", start.map(TimeUtil::formatUtc).orElse("—")
        )));
        sender.sendMessage(lang.msg("admin.status.ffa", Map.of(
                "time", ffa.map(TimeUtil::formatUtc).orElse("—"),
                "source", ffa.isPresent() ? ffaSource : "—"
        )));
        sender.sendMessage(lang.msg("admin.status.end", Map.of(
                "time", end.map(TimeUtil::formatUtc).orElse("—")
        )));
        sender.sendMessage(lang.msg("admin.status.remaining", Map.of(
                "countdown", TimeUtil.formatCountdown(remainingSecs)
        )));
        sender.sendMessage(lang.msg("admin.status.flags", Map.of(
                "ffa_teleported", String.valueOf(eventManager.isFfaTeleported()),
                "ceremony_done", String.valueOf(eventManager.isCeremonyDone())
        )));
        sender.sendMessage(lang.msg("admin.status.kills", Map.of(
                "rows", String.valueOf(killRows),
                "top_killer", topName,
                "top_kills", topKills
        )));
        sender.sendMessage(lang.msg("admin.status.arena", Map.of(
                "world", config.arenaWorld(),
                "cuboid", formatCuboid(cuboid),
                "center", centerLooksSet ? "set" : "default?"
        )));
        sender.sendMessage(lang.msg("admin.status.vanish", Map.of(
                "backend", vanishService.backendLabel()
        )));
        sender.sendMessage(lang.msg("admin.status.eligible", Map.of(
                "count", String.valueOf(eligible)
        )));
    }

    private static String formatCuboid(CuboidBounds c) {
        return String.format(Locale.ROOT, "%.0f,%.0f,%.0f → %.0f,%.0f,%.0f",
                c.minX(), c.minY(), c.minZ(), c.maxX(), c.maxY(), c.maxZ());
    }

    public void startNow(CommandSender sender) {
        Instant now = Instant.now();
        ScheduleJumps.Times times = ScheduleJumps.startNow(
                now, eventManager.end().orElse(null), config.ffaBeforeEndSeconds()
        );
        // Force jumps go live immediately (unlike host schedule edits which pause).
        eventManager.applyScheduleAndUnpause(times, now);
        eventScheduler.refreshDisplayNow();
        sender.sendMessage(lang.msg("admin.startnow-ok", Map.of(
                "phase", eventManager.phase().name(),
                "time", TimeUtil.formatUtc(times.start())
        )));
    }

    public void ffaNow(CommandSender sender) {
        Instant now = Instant.now();
        ScheduleJumps.Times times = ScheduleJumps.ffaNow(
                now,
                eventManager.start().orElse(null),
                eventManager.end().orElse(null),
                config.ffaBeforeEndSeconds()
        );
        eventManager.applyScheduleAndUnpause(times, now);
        eventScheduler.refreshDisplayNow();
        sender.sendMessage(lang.msg("admin.ffanow-ok", Map.of(
                "phase", eventManager.phase().name(),
                "time", TimeUtil.formatUtc(now)
        )));
    }

    public void endNow(CommandSender sender) {
        Instant now = Instant.now();
        ScheduleJumps.Times times = ScheduleJumps.endNow(now, eventManager.start().orElse(null));
        eventManager.applyScheduleAndUnpause(times, now);
        eventScheduler.refreshDisplayNow();
        sender.sendMessage(lang.msg("admin.endnow-ok", Map.of(
                "phase", eventManager.phase().name()
        )));
    }

    public void pause(CommandSender sender) {
        eventManager.pause();
        eventScheduler.refreshDisplayNow();
        sender.sendMessage(lang.msg("admin.paused-ok"));
    }

    public void unpause(CommandSender sender) {
        EventPhase p = eventManager.unpause(Instant.now());
        // Bossbar/scoreboard must come back immediately — do not wait for the next 1s tick.
        eventScheduler.refreshDisplayNow();
        sender.sendMessage(lang.msg("admin.unpaused-ok", Map.of("phase", p.name())));
    }

    public void forceTp(CommandSender sender) {
        String err = eventScheduler.forceFfaTeleport();
        if (err == null) {
            sender.sendMessage(lang.msg("admin.forcetp-ok"));
            return;
        }
        sender.sendMessage(lang.msg("admin.forcetp-" + err));
    }

    public void forceCeremony(CommandSender sender) {
        String err = eventScheduler.forceCeremony();
        if (err == null) {
            sender.sendMessage(lang.msg("admin.forceceremony-ok"));
            return;
        }
        sender.sendMessage(lang.msg("admin.forceceremony-" + err));
    }

    public void resetFlags(CommandSender sender) {
        eventManager.clearFlags();
        sender.sendMessage(lang.msg("admin.resetflags-ok"));
    }

    public void eligible(CommandSender sender) {
        List<Player> list = ffaSpawnService.eligiblePlayers();
        sender.sendMessage(lang.msg("admin.eligible-header", Map.of("count", String.valueOf(list.size()))));
        if (list.isEmpty()) {
            sender.sendMessage(lang.msg("admin.eligible-empty"));
            return;
        }
        for (Player p : list) {
            sender.sendMessage(lang.msg("admin.eligible-entry", Map.of("player", p.getName())));
        }
    }

    public void clearKills(CommandSender sender, boolean confirmed) {
        if (!confirmed) {
            sender.sendMessage(lang.msg("admin.clearkills-confirm"));
            return;
        }
        killService.clearAll();
        sender.sendMessage(lang.msg("admin.clearkills-ok"));
    }

    public void reload(CommandSender sender) {
        config.reload();
        lang.reload();
        effects.rebuildCache();
        vanishService.rebind();
        sender.sendMessage(lang.msg("messages.reload-ok"));
    }

    /**
     * Time-jump helper so phaseAt(now) matches the target. Thin aliases over schedule controls.
     */
    public void setPhase(CommandSender sender, String rawPhase) {
        if (rawPhase == null || rawPhase.isBlank()) {
            sender.sendMessage(lang.msg("admin.phase-usage"));
            return;
        }
        String name = rawPhase.trim().toUpperCase(Locale.ROOT);
        EventPhase target;
        try {
            target = EventPhase.valueOf(name);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(lang.msg("admin.phase-usage"));
            return;
        }
        Instant now = Instant.now();
        switch (target) {
            case IDLE -> {
                eventManager.clearSchedule();
                sender.sendMessage(lang.msg("admin.phase-ok", Map.of("phase", "IDLE")));
            }
            case PAUSED -> {
                eventManager.pause();
                sender.sendMessage(lang.msg("admin.phase-ok", Map.of("phase", "PAUSED")));
            }
            case COUNTDOWN -> {
                ScheduleJumps.Times times = ScheduleJumps.countdown(
                        now,
                        eventManager.end().orElse(null),
                        COUNTDOWN_LEAD_SECONDS,
                        config.ffaBeforeEndSeconds()
                );
                eventManager.applyScheduleAndUnpause(times, now);
                sender.sendMessage(lang.msg("admin.phase-ok", Map.of(
                        "phase", eventManager.phase().name()
                )));
            }
            case HUNT -> {
                ScheduleJumps.Times times = ScheduleJumps.hunt(
                        now, eventManager.end().orElse(null), config.ffaBeforeEndSeconds()
                );
                eventManager.applyScheduleAndUnpause(times, now);
                sender.sendMessage(lang.msg("admin.phase-ok", Map.of(
                        "phase", eventManager.phase().name()
                )));
            }
            case FFA -> ffaNow(sender);
            case ENDED -> endNow(sender);
        }
    }

    public void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/preciv admin <status|startnow|ffanow|endnow|pause|unpause|forcetp|forceceremony|resetflags|"
                        + "eligible|clearkills|reload|phase|wand|set …>"
        ));
    }
}
