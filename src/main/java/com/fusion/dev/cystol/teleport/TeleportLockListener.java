package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.config.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hunt-only movement lock layers:
 * <ul>
 *   <li>Cancel blocked TP commands (incl. {@code /spawn})</li>
 *   <li>Cancel non-exempt teleports (esp. cross-world hub TPs)</li>
 *   <li>Revert illegal world changes (not cancellable — snap back next tick)</li>
 * </ul>
 */
public final class TeleportLockListener implements Listener {

    private final JavaPlugin plugin;
    private final TeleportLockService service;
    private final Lang lang;

    public TeleportLockListener(JavaPlugin plugin, TeleportLockService service, Lang lang) {
        this.plugin = plugin;
        this.service = service;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!service.shouldBlockCommand(player, event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(lang.msg("teleport.locked"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!service.shouldBlockTeleport(player, from, to, event.getCause())) {
            // Allowed: remember destination so a following world-change is not reverted
            // (e.g. nether portal — TP cause exempt, then PlayerChangedWorldEvent fires).
            if (to != null && to.getWorld() != null) {
                service.rememberSafeLocation(player, to);
            } else if (from != null) {
                service.rememberSafeLocation(player, from);
            }
            return;
        }
        event.setCancelled(true);
        player.sendMessage(lang.msg("teleport.locked"));
        if (from != null && from.getWorld() != null) {
            service.rememberSafeLocation(player, from);
        }
    }

    /**
     * World changes cannot be cancelled. If lock would have blocked the move, snap the player
     * back to their last safe location (or previous world's spawn as last resort).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!service.shouldBlockWorldChange(player, event.getFrom())) {
            service.rememberSafeLocation(player);
            return;
        }

        Location safe = service.lastSafeLocation(player);
        if (safe == null || safe.getWorld() == null) {
            if (event.getFrom() != null) {
                safe = event.getFrom().getSpawnLocation();
            }
        }
        if (safe == null || safe.getWorld() == null || plugin == null) {
            return;
        }

        final Location dest = safe.clone();
        // Brief allow so our own revert is not re-cancelled by onTeleport
        service.allowTemporarily(player, 2_000L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!service.isLockActive() || service.isFullyExempt(player)) {
                service.rememberSafeLocation(player);
                return;
            }
            player.teleport(dest);
            service.rememberSafeLocation(player, dest);
            player.sendMessage(lang.msg("teleport.locked"));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin == null) {
            service.rememberSafeLocation(player);
            return;
        }
        // Delay so hub relocate plugins settle, then store feet
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                service.rememberSafeLocation(player);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                service.rememberSafeLocation(player);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clearPlayer(event.getPlayer());
    }
}
