package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.config.Lang;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Hunt-only: cancel blocked TP commands and non-exempt teleports when outside spawn/arena.
 */
public final class TeleportLockListener implements Listener {

    private final TeleportLockService service;
    private final Lang lang;

    public TeleportLockListener(TeleportLockService service, Lang lang) {
        this.service = service;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!service.shouldBlockCommand(player, event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(lang.msg("teleport.locked"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!service.shouldBlockTeleport(player, event.getFrom(), event.getCause())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(lang.msg("teleport.locked"));
    }
}
