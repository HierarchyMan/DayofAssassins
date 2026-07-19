package com.fusion.dev.cystol.teleport;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Live HUNT only: if feet are in spawn cuboid or FFA arena cuboid → BetterRTP.
 * Hooks join, world-change, and completed teleports (same-world warps into zones).
 * Short delay so other plugins finish relocating the player.
 */
public final class SpawnHuntRtpJoinListener implements Listener {

    private static final long DELAY_TICKS = 10L;

    private final JavaPlugin plugin;
    private final SpawnHuntRtpService spawnHuntRtpService;

    public SpawnHuntRtpJoinListener(JavaPlugin plugin, SpawnHuntRtpService spawnHuntRtpService) {
        this.plugin = plugin;
        this.spawnHuntRtpService = spawnHuntRtpService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        schedule(event.getPlayer(), "join");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        schedule(event.getPlayer(), "world-change");
    }

    /**
     * Same-world entry into spawn/arena (e.g. /warp) does not fire world-change.
     * After a completed teleport, re-check destination feet.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // Skip if we (or temp allow) are mid plugin RTP — avoid loops; eligibility re-check still OK
        // because after RTP they leave the cuboid.
        Player player = event.getPlayer();
        schedule(player, "teleport");
    }

    private void schedule(Player player, String reason) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if ("join".equals(reason)) {
                spawnHuntRtpService.maybeRtpOnJoin(player);
            } else if ("world-change".equals(reason)) {
                spawnHuntRtpService.maybeRtpOnWorldChange(player);
            } else {
                spawnHuntRtpService.maybeRtpAfterTeleport(player);
            }
        }, DELAY_TICKS);
    }
}
