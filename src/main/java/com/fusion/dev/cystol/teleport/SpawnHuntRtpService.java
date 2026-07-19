package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Hunt-only: BetterRTP players standing in the spawn cuboid <em>or</em> the FFA arena cuboid.
 * Same-world setups are the normal case (no separate spawn world required).
 * <ul>
 *   <li>Kickoff mass dump once when entering HUNT (persisted flag)</li>
 *   <li>Join / world-change / post-teleport while live HUNT if feet land in either cuboid</li>
 * </ul>
 */
public final class SpawnHuntRtpService {

    public static final String BYPASS_PERM = "preciv.spawn.rtp.bypass";
    public static final int DEFAULT_BATCH_SIZE = 8;

    private final JavaPlugin plugin;
    private final EventManager eventManager;
    private final PluginConfig config;
    private final TeleportLockService teleportLock;
    private final BetterRtpBridge betterRtp;
    private final NoBypassService noBypass;
    private final Logger logger;

    private final AtomicBoolean kickoffInProgress = new AtomicBoolean(false);
    private BukkitTask batchTask;

    public SpawnHuntRtpService(
            JavaPlugin plugin,
            EventManager eventManager,
            PluginConfig config,
            TeleportLockService teleportLock,
            BetterRtpBridge betterRtp,
            NoBypassService noBypass,
            Logger logger
    ) {
        this.plugin = plugin;
        this.eventManager = eventManager;
        this.config = config;
        this.teleportLock = teleportLock;
        this.betterRtp = betterRtp;
        this.noBypass = noBypass;
        this.logger = logger;
    }

    public void shutdown() {
        cancelBatch();
        kickoffInProgress.set(false);
    }

    private void cancelBatch() {
        if (batchTask != null) {
            batchTask.cancel();
            batchTask = null;
        }
    }

    /**
     * Whether hunt RTP may run right now for join / force (live HUNT, not paused, enabled).
     */
    public boolean isLiveHuntRtpWindow() {
        return isLiveHuntRtpWindow(config.huntRtpEnabled(), eventManager.isPaused(), eventManager.phase());
    }

    /**
     * Pure gate: config enabled, not paused, phase is exactly {@link EventPhase#HUNT}
     * (not FFA / COUNTDOWN / PAUSED / etc.).
     */
    public static boolean isLiveHuntRtpWindow(boolean huntRtpEnabled, boolean paused, EventPhase phase) {
        if (!huntRtpEnabled || paused) {
            return false;
        }
        return phase == EventPhase.HUNT;
    }

    /**
     * Pure gate for kickoff mass dump on phase change.
     * Fires when entering HUNT from pre-event (COUNTDOWN / IDLE / …), not from PAUSED recovery.
     */
    public static boolean shouldKickoffOnPhaseChange(EventPhase from, EventPhase to) {
        return to == EventPhase.HUNT && from != EventPhase.HUNT && from != EventPhase.PAUSED;
    }

    /**
     * Pure location gate: feet in configured spawn cuboid (world-checked).
     */
    public static boolean matchesSpawnCuboid(
            boolean spawnConfigured,
            String spawnWorld,
            CuboidBounds cuboid,
            String playerWorld,
            double x,
            double y,
            double z
    ) {
        if (!spawnConfigured || cuboid == null) {
            return false;
        }
        if (!worldNamesEqual(spawnWorld, playerWorld)) {
            return false;
        }
        return cuboid.contains(x, y, z);
    }

    /**
     * Pure location gate: feet in FFA arena cuboid (world-checked).
     */
    public static boolean matchesArenaCuboid(
            String arenaWorld,
            CuboidBounds cuboid,
            String playerWorld,
            double x,
            double y,
            double z
    ) {
        if (cuboid == null || !worldNamesEqual(arenaWorld, playerWorld)) {
            return false;
        }
        return cuboid.contains(x, y, z);
    }

    /**
     * Pure combined gate: spawn cuboid OR arena cuboid (either is enough).
     */
    public static boolean matchesEvictZone(
            boolean spawnConfigured,
            String spawnWorld,
            CuboidBounds spawnCuboid,
            String arenaWorld,
            CuboidBounds arenaCuboid,
            String playerWorld,
            double x,
            double y,
            double z
    ) {
        if (matchesSpawnCuboid(spawnConfigured, spawnWorld, spawnCuboid, playerWorld, x, y, z)) {
            return true;
        }
        return matchesArenaCuboid(arenaWorld, arenaCuboid, playerWorld, x, y, z);
    }

    private static boolean worldNamesEqual(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    /**
     * Whether RTP bypass perm is honored (false when nobypass mode is active).
     */
    public boolean hasRtpBypass(Player player) {
        if (player == null) {
            return false;
        }
        if (noBypass != null && noBypass.isActive()) {
            return false;
        }
        return player.hasPermission(BYPASS_PERM);
    }

    /**
     * Location gate used by kickoff, join, world-change, post-TP.
     * Spectators and (when honored) bypass perm excluded.
     */
    public boolean isEligibleForHuntRtp(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (hasRtpBypass(player)) {
            return false;
        }
        Location loc = player.getLocation();
        if (loc.getWorld() == null) {
            return false;
        }
        return matchesEvictZone(
                config.spawnZoneConfigured(),
                config.spawnWorld(),
                config.spawnCuboid(),
                config.arenaWorld(),
                config.arenaCuboid(),
                loc.getWorld().getName(),
                loc.getX(),
                loc.getY(),
                loc.getZ()
        );
    }

    /** @deprecated use {@link #isEligibleForHuntRtp(Player)} */
    @Deprecated
    public boolean isEligibleInSpawnCuboid(Player player) {
        return isEligibleForHuntRtp(player);
    }

    public List<Player> eligibleOnline() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isEligibleForHuntRtp(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** @deprecated use {@link #eligibleOnline()} */
    @Deprecated
    public List<Player> eligibleOnlineInSpawn() {
        return eligibleOnline();
    }

    /**
     * One-shot kickoff mass dump. Marks {@code spawn_rtp_done} even if 0 players / no bridge
     * so phase ticks do not retry forever.
     *
     * @return null on accepted start (or intentional no-op success), else reason key suffix for admin messages
     */
    public String runKickoffIfNeeded() {
        if (!config.huntRtpEnabled()) {
            eventManager.markSpawnRtpDone();
            return "disabled";
        }
        if (eventManager.isSpawnRtpDone()) {
            return "already-done";
        }
        if (!kickoffInProgress.compareAndSet(false, true)) {
            return "in-progress";
        }
        try {
            if (!betterRtp.isAvailable()) {
                eventManager.markSpawnRtpDone();
                logger.warning("Hunt spawn/arena RTP kickoff skipped: BetterRTP unavailable ("
                        + betterRtp.backendLabel() + ")");
                kickoffInProgress.set(false);
                return "no-bridge";
            }

            List<Player> eligible = eligibleOnline();
            eventManager.markSpawnRtpDone();
            if (eligible.isEmpty()) {
                logger.info("Hunt spawn/arena RTP kickoff: 0 eligible players in spawn or arena cuboid");
                kickoffInProgress.set(false);
                return null;
            }
            startBatch(eligible, () -> {
                kickoffInProgress.set(false);
                logger.info("Hunt spawn/arena RTP kickoff complete for " + eligible.size() + " player(s)");
            });
            return null;
        } catch (RuntimeException e) {
            kickoffInProgress.set(false);
            logger.warning("Hunt spawn/arena RTP kickoff failed: " + e.getMessage());
            if (!eventManager.isSpawnRtpDone()) {
                eventManager.markSpawnRtpDone();
            }
            return "failed";
        }
    }

    private volatile int lastForceCount;

    public int lastForceCount() {
        return lastForceCount;
    }

    /**
     * Admin force: clear one-shot, re-run mass dump if currently live HUNT.
     *
     * @return null on success with count via {@link #lastForceCount()}, else reason suffix
     */
    public String forceKickoff() {
        if (!config.huntRtpEnabled()) {
            return "disabled";
        }
        if (!isLiveHuntRtpWindow()) {
            return "not-hunt";
        }
        if (!betterRtp.isAvailable()) {
            return "no-bridge";
        }
        if (kickoffInProgress.get()) {
            return "in-progress";
        }
        eventManager.clearSpawnRtpDone();
        lastForceCount = eligibleOnline().size();
        String err = runKickoffIfNeeded();
        if (err == null) {
            return null;
        }
        return err;
    }

    /**
     * Single-player path. Applies temp teleport-lock allow then BetterRTP.
     *
     * @return true if request was handed to BetterRTP
     */
    public boolean rtpPlayerIfEligible(Player player) {
        if (!config.huntRtpEnabled() || !betterRtp.isAvailable()) {
            return false;
        }
        if (!isEligibleForHuntRtp(player)) {
            return false;
        }
        return rtpPlayerUnchecked(player);
    }

    /**
     * Join / world-change / post-TP path: only while live HUNT window.
     */
    public void maybeRtpWhileLive(Player player, String reason) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!isLiveHuntRtpWindow()) {
            return;
        }
        // Avoid re-entry while a plugin RTP allow window is open (BetterRTP in flight).
        if (teleportLock.hasTemporaryAllow(player)) {
            return;
        }
        if (!isEligibleForHuntRtp(player)) {
            return;
        }
        if (!betterRtp.isAvailable()) {
            return;
        }
        boolean ok = rtpPlayerUnchecked(player);
        if (ok) {
            String tag = reason == null || reason.isBlank() ? "event" : reason;
            logger.info("Hunt spawn/arena RTP (" + tag + ") for " + player.getName());
        }
    }

    /** Join path (kept name for listeners). */
    public void maybeRtpOnJoin(Player player) {
        maybeRtpWhileLive(player, "join");
    }

    public void maybeRtpOnWorldChange(Player player) {
        maybeRtpWhileLive(player, "world-change");
    }

    public void maybeRtpAfterTeleport(Player player) {
        maybeRtpWhileLive(player, "teleport");
    }

    /**
     * Periodic safety net (same-world walk-in): RTP anyone still standing in spawn/arena
     * during live HUNT. Skips players mid temp-allow / already ineligible.
     */
    public void sweepOnlineIfLive() {
        if (!isLiveHuntRtpWindow() || !betterRtp.isAvailable()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            maybeRtpWhileLive(p, "sweep");
        }
    }

    private boolean rtpPlayerUnchecked(Player player) {
        teleportLock.allowTemporarily(player, config.huntRtpBypassMs());
        World dest = resolveRtpWorld(player);
        return betterRtp.requestRtp(player, dest);
    }

    /**
     * Configured dest world if set and loaded; otherwise player's current world (same-world RTP).
     */
    private World resolveRtpWorld(Player player) {
        String configured = config.huntRtpWorld();
        if (configured != null && !configured.isBlank()) {
            World w = Bukkit.getWorld(configured);
            if (w != null) {
                return w;
            }
            logger.warning("Hunt RTP world not loaded: " + configured + " — using player world for "
                    + player.getName());
        }
        return player.getWorld();
    }

    private void startBatch(List<Player> players, Runnable onComplete) {
        cancelBatch();
        if (players.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        final List<Player> ordered = new ArrayList<>(players);
        final int batch = DEFAULT_BATCH_SIZE;
        final int[] index = {0};
        final boolean[] completed = {false};
        batchTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (completed[0]) {
                return;
            }
            int start = index[0];
            int end = Math.min(start + batch, ordered.size());
            for (int i = start; i < end; i++) {
                Player p = ordered.get(i);
                if (p == null || !p.isOnline()) {
                    continue;
                }
                // Re-check zone at fire time so people who walked out aren't forced
                if (!isEligibleForHuntRtp(p)) {
                    continue;
                }
                rtpPlayerUnchecked(p);
            }
            index[0] = end;
            if (index[0] >= ordered.size()) {
                completed[0] = true;
                if (batchTask != null) {
                    batchTask.cancel();
                    batchTask = null;
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, 0L, 1L);
    }
}
