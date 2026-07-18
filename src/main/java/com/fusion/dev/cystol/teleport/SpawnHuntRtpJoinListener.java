package com.fusion.dev.cystol.teleport;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Delayed join check: live HUNT only, not paused, feet in spawn cuboid → BetterRTP.
 * 1-tick delay so other plugins can finish relocating the player on join.
 */
public final class SpawnHuntRtpJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final SpawnHuntRtpService spawnHuntRtpService;

    public SpawnHuntRtpJoinListener(JavaPlugin plugin, SpawnHuntRtpService spawnHuntRtpService) {
        this.plugin = plugin;
        this.spawnHuntRtpService = spawnHuntRtpService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Short delay: hub plugins often relocate players after join (matches compass join delay).
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawnHuntRtpService.maybeRtpOnJoin(player), 10L);
    }
}
