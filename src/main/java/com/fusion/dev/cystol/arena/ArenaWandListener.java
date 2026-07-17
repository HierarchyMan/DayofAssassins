package com.fusion.dev.cystol.arena;

import com.fusion.dev.cystol.compass.CompassService;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;

public final class ArenaWandListener implements Listener {

    private final CompassService compassService;
    private final PluginConfig config;
    private final Lang lang;

    public ArenaWandListener(CompassService compassService, PluginConfig config, Lang lang) {
        this.compassService = compassService;
        this.config = config;
        this.lang = lang;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!compassService.isWand(event.getItem())) {
            return;
        }
        if (!event.getPlayer().hasPermission("preciv.admin")) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Location loc = block.getLocation();
        Player player = event.getPlayer();
        config.setArenaWorld(loc.getWorld().getName());
        if (action == Action.LEFT_CLICK_BLOCK) {
            config.setPos1(loc.getX(), loc.getY(), loc.getZ());
            player.sendMessage(lang.msg("admin.pos1-set", Map.of(
                    "x", String.valueOf(loc.getBlockX()),
                    "y", String.valueOf(loc.getBlockY()),
                    "z", String.valueOf(loc.getBlockZ())
            )));
        } else {
            config.setPos2(loc.getX(), loc.getY(), loc.getZ());
            player.sendMessage(lang.msg("admin.pos2-set", Map.of(
                    "x", String.valueOf(loc.getBlockX()),
                    "y", String.valueOf(loc.getBlockY()),
                    "z", String.valueOf(loc.getBlockZ())
            )));
        }
    }
}
