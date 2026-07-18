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
 * Hunt-only: BetterRTP players standing in the configured spawn cuboid.
 * <ul>
 *   <li>Kickoff mass dump once when entering HUNT (persisted flag)</li>
 *   <li>Per-join dump while live HUNT (not paused), if join location is in cuboid</li>
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
    private final Logger logger;

    private final AtomicBoolean kickoffInProgress = new AtomicBoolean(false);
    private BukkitTask batchTask;

    public SpawnHuntRtpService(
            JavaPlugin plugin,
            EventManager eventManager,
            PluginConfig config,
            TeleportLockService teleportLock,
            BetterRtpBridge betterRtp,
            Logger logger
    ) {
        this.plugin = plugin;
        this.eventManager = eventManager;
        this.config = config;
        this.teleportLock = teleportLock;
        this.betterRtp = betterRtp;
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
     * Whether hunt-spawn RTP may run right now for join / force (live HUNT, not paused, enabled).
     */
    public boolean isLiveHuntRtpWindow() {
        if (!config.huntRtpEnabled()) {
            return false;
        }
        if (eventManager.isPaused()) {
            return false;
        }
        return eventManager.phase() == EventPhase.HUNT;
    }

    /**
     * Location gate used by kickoff and join. Spectators and bypass perm excluded.
     */
    public boolean isEligibleInSpawnCuboid(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (!config.spawnZoneConfigured()) {
            return false;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (player.hasPermission(BYPASS_PERM)) {
            return false;
        }
        Location loc = player.getLocation();
        if (loc.getWorld() == null) {
            return false;
        }
        String worldName = config.spawnWorld();
        if (worldName == null || !loc.getWorld().getName().equals(worldName)) {
            return false;
        }
        CuboidBounds cuboid = config.spawnCuboid();
        return cuboid.contains(loc.getX(), loc.getY(), loc.getZ());
    }

    public List<Player> eligibleOnlineInSpawn() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isEligibleInSpawnCuboid(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * One-shot kickoff mass dump. Marks {@code spawn_rtp_done} even if 0 players / unconfigured / no bridge
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
            if (!config.spawnZoneConfigured()) {
                eventManager.markSpawnRtpDone();
                logger.warning("Hunt spawn RTP kickoff skipped: spawn zone not configured");
                kickoffInProgress.set(false);
                return "unconfigured";
            }
            if (!betterRtp.isAvailable()) {
                eventManager.markSpawnRtpDone();
                logger.warning("Hunt spawn RTP kickoff skipped: BetterRTP unavailable (" + betterRtp.backendLabel() + ")");
                kickoffInProgress.set(false);
                return "no-bridge";
            }

            List<Player> eligible = eligibleOnlineInSpawn();
            eventManager.markSpawnRtpDone();
            if (eligible.isEmpty()) {
                logger.info("Hunt spawn RTP kickoff: 0 players in spawn cuboid");
                kickoffInProgress.set(false);
                return null;
            }
            startBatch(eligible, () -> {
                kickoffInProgress.set(false);
                logger.info("Hunt spawn RTP kickoff complete for " + eligible.size() + " player(s)");
            });
            return null;
        } catch (RuntimeException e) {
            kickoffInProgress.set(false);
            logger.warning("Hunt spawn RTP kickoff failed: " + e.getMessage());
            // still mark done to avoid infinite retry; admin can forcespawnrtp / resetflags
            if (!eventManager.isSpawnRtpDone()) {
                eventManager.markSpawnRtpDone();
            }
            return "failed";
        }
    }

    /**
     * Admin force: clear one-shot, re-run mass dump if currently live HUNT.
     *
     * @return null on success with count message data via {@link #lastForceCount()}, else reason suffix
     */
    private volatile int lastForceCount;

    public int lastForceCount() {
        return lastForceCount;
    }

    public String forceKickoff() {
        if (!config.huntRtpEnabled()) {
            return "disabled";
        }
        if (!isLiveHuntRtpWindow()) {
            return "not-hunt";
        }
        if (!config.spawnZoneConfigured()) {
            return "unconfigured";
        }
        if (!betterRtp.isAvailable()) {
            return "no-bridge";
        }
        if (kickoffInProgress.get()) {
            return "in-progress";
        }
        eventManager.clearSpawnRtpDone();
        lastForceCount = eligibleOnlineInSpawn().size();
        String err = runKickoffIfNeeded();
        if (err == null) {
            return null;
        }
        // already-done shouldn't happen after clear; map others
        return err;
    }

    /**
     * Single-player path for join (and internal batch). Applies temp teleport-lock allow then BetterRTP.
     *
     * @return true if request was handed to BetterRTP
     */
    public boolean rtpPlayerIfEligible(Player player) {
        if (!config.huntRtpEnabled() || !betterRtp.isAvailable()) {
            return false;
        }
        if (!isEligibleInSpawnCuboid(player)) {
            return false;
        }
        return rtpPlayerUnchecked(player);
    }

    /**
     * Join path: only while live HUNT window; re-check eligibility after delay.
     */
    public void maybeRtpOnJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!isLiveHuntRtpWindow()) {
            return;
        }
        if (!isEligibleInSpawnCuboid(player)) {
            return;
        }
        if (!betterRtp.isAvailable()) {
            return;
        }
        boolean ok = rtpPlayerUnchecked(player);
        if (ok) {
            logger.info("Hunt spawn RTP on join for " + player.getName());
        }
    }

    private boolean rtpPlayerUnchecked(Player player) {
        teleportLock.allowTemporarily(player, config.huntRtpBypassMs());
        World dest = resolveRtpWorld(player);
        return betterRtp.requestRtp(player, dest);
    }

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
                // Re-check cuboid at fire time so people who walked out aren't forced
                if (!isEligibleInSpawnCuboid(p)) {
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
