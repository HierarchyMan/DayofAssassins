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

import java.util.Map;

/**
 * Hunt-only movement lock layers:
 * <ul>
 *   <li>Cancel blocked TP commands (incl. {@code /spawn}) — LOWEST so we win before other plugins</li>
 *   <li>Cancel non-exempt teleports (esp. cross-world hub TPs)</li>
 *   <li>Revert illegal world changes (not cancellable — snap back next tick)</li>
 *   <li>Consume short temp RTP allow when the plugin TP lands</li>
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

    /**
     * LOWEST: intercept blacklisted commands before Essentials/etc. can run them.
     * Temp RTP allow does not open commands. Message: {@code teleport.command-blocked}.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String raw = event.getMessage();
        boolean block = service.shouldBlockCommand(player, raw);
        if (service.isDebug()) {
            service.debug(player.getName() + " cmd " + service.explainCommand(player, raw)
                    + " raw=" + raw);
        }
        if (!block) {
            return;
        }
        event.setCancelled(true);
        String label = TeleportLockService.primaryCommandLabel(raw);
        if (label.isEmpty()) {
            label = "command";
        }
        player.sendMessage(lang.msg("teleport.command-blocked", Map.of("command", label)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        boolean block = service.shouldBlockTeleport(player, from, to, cause);
        if (service.isDebug()) {
            service.debug(player.getName() + " tp " + service.explainTeleport(player, from, to, cause));
        }
        if (!block) {
            // Allowed: remember destination so a following world-change is not reverted
            // (e.g. nether portal — TP cause exempt, then PlayerChangedWorldEvent fires).
            if (to != null && to.getWorld() != null) {
                service.rememberSafeLocation(player, to);
            } else if (from != null) {
                service.rememberSafeLocation(player, from);
            }
            // Consume short RTP allow as soon as the plugin TP actually lands
            if (service.hasTemporaryAllow(player) && TeleportLockService.isPluginStyleCause(cause)) {
                service.clearTemporaryAllow(player);
                service.debug(player.getName() + " temp-rtp-allow consumed (plugin TP landed)");
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
        boolean block = service.shouldBlockWorldChange(player, event.getFrom());
        if (service.isDebug()) {
            service.debug(player.getName() + " world " + service.explainWorldChange(player, event.getFrom()));
        }
        if (!block) {
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
            service.debug(player.getName() + " world-revert aborted (no safe dest)");
            return;
        }

        final Location dest = safe.clone();
        // Only a few ticks so our own revert is not re-cancelled by onTeleport
        service.allowTemporarilyTicks(player, TeleportLockService.REVERT_ALLOW_TICKS);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!service.isLockActive() || service.hasBypass(player)) {
                service.rememberSafeLocation(player);
                service.clearTemporaryAllow(player);
                return;
            }
            player.teleport(dest);
            service.rememberSafeLocation(player, dest);
            service.clearTemporaryAllow(player);
            player.sendMessage(lang.msg("teleport.locked"));
            service.debug(player.getName() + " world-reverted → " + dest.getWorld().getName());
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
                if (service.isDebug() && service.isLockActive()) {
                    service.debug(player.getName() + " join safe remembered world="
                            + (player.getWorld() != null ? player.getWorld().getName() : "null")
                            + " phase=HUNT lock=on");
                }
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
