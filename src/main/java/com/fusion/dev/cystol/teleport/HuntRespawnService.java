package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Hunt-only: after death, prefer Essentials home; otherwise force BetterRTP.
 * Opens a short teleport-lock allow so respawn placement is not cancelled.
 */
public final class HuntRespawnService implements Listener {

    private final JavaPlugin plugin;
    private final EventManager eventManager;
    private final PluginConfig config;
    private final TeleportLockService teleportLock;
    private final SpawnHuntRtpService spawnHuntRtp;
    private final EssentialsHomeBridge homes;
    private final Logger logger;
    private final Set<UUID> pendingForceRtp = ConcurrentHashMap.newKeySet();

    public HuntRespawnService(
            JavaPlugin plugin,
            EventManager eventManager,
            PluginConfig config,
            TeleportLockService teleportLock,
            SpawnHuntRtpService spawnHuntRtp,
            EssentialsHomeBridge homes,
            Logger logger
    ) {
        this.plugin = plugin;
        this.eventManager = eventManager;
        this.config = config;
        this.teleportLock = teleportLock;
        this.spawnHuntRtp = spawnHuntRtp;
        this.homes = homes;
        this.logger = logger;
    }

    public static boolean shouldRelocate(
            boolean enabled,
            boolean paused,
            EventPhase phase,
            GameMode mode
    ) {
        if (!enabled || paused || phase != EventPhase.HUNT) {
            return false;
        }
        return mode != GameMode.SPECTATOR;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!shouldRelocate(
                config.respawnRelocateEnabled(),
                eventManager.isPaused(),
                eventManager.phase(),
                player.getGameMode()
        )) {
            return;
        }

        // Always open a short allow so bed/world-spawn placement is not TP-lock cancelled
        long ticks = config.huntRtpBypassTicks();
        teleportLock.allowTemporarilyTicks(player, ticks);
        teleportLock.markRespawnAllow(player);

        if (config.respawnPreferEssentialsHome()) {
            Location home = homes.findHome(player);
            if (home != null) {
                event.setRespawnLocation(home);
                pendingForceRtp.remove(player.getUniqueId());
                logger.info("Hunt respawn → Essentials home for " + player.getName());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        teleportLock.rememberSafeLocation(player);
                    }
                });
                return;
            }
        }

        pendingForceRtp.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> finishForceRtp(player.getUniqueId()), 2L);
    }

    private void finishForceRtp(UUID uuid) {
        if (!pendingForceRtp.remove(uuid)) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!shouldRelocate(
                config.respawnRelocateEnabled(),
                eventManager.isPaused(),
                eventManager.phase(),
                player.getGameMode()
        )) {
            return;
        }
        boolean ok = spawnHuntRtp.forceRtpPlayer(player);
        if (ok) {
            logger.info("Hunt respawn → BetterRTP for " + player.getName());
        } else {
            logger.warning("Hunt respawn RTP failed for " + player.getName()
                    + " (BetterRTP unavailable or request rejected)");
            teleportLock.rememberSafeLocation(player);
        }
    }
}
