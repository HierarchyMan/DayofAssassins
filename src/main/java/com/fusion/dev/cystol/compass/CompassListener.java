package com.fusion.dev.cystol.compass;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.fx.EffectService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CompassListener implements Listener {

    private final JavaPlugin plugin;
    private final CompassService compassService;
    private final CompassGui compassGui;
    private final EventManager eventManager;
    private final Lang lang;
    private final PluginConfig config;
    private final EffectService effects;
    private final Set<UUID> fullInvHinted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> hintTasks = new ConcurrentHashMap<>();

    public CompassListener(
            JavaPlugin plugin,
            CompassService compassService,
            CompassGui compassGui,
            EventManager eventManager,
            Lang lang,
            PluginConfig config,
            EffectService effects
    ) {
        this.plugin = plugin;
        this.compassService = compassService;
        this.compassGui = compassGui;
        this.eventManager = eventManager;
        this.lang = lang;
        this.config = config;
        this.effects = effects;
    }

    private boolean eventActive() {
        EventPhase p = eventManager.phase();
        return p == EventPhase.HUNT || p == EventPhase.FFA;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!eventActive()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryGiveOnJoin(player), 10L);
    }

    private void tryGiveOnJoin(Player player) {
        if (!player.isOnline() || !eventActive()) {
            return;
        }
        CompassService.GiveResult result = compassService.tryGive(player);
        if (result == CompassService.GiveResult.GIVEN) {
            player.sendMessage(lang.msg("compass.received"));
            cancelHint(player.getUniqueId());
        } else if (result == CompassService.GiveResult.INVENTORY_FULL) {
            startFullInvHint(player);
        }
    }

    public void giveToAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            CompassService.GiveResult result = compassService.tryGive(player);
            if (result == CompassService.GiveResult.GIVEN) {
                player.sendMessage(lang.msg("compass.received"));
            } else if (result == CompassService.GiveResult.INVENTORY_FULL) {
                startFullInvHint(player);
            }
        }
    }

    private void startFullInvHint(Player player) {
        UUID id = player.getUniqueId();
        if (!fullInvHinted.add(id)) {
            return;
        }
        cancelHint(id);
        int seconds = Math.max(1, config.fullInvActionbarSeconds());
        final int[] left = {seconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || left[0]-- <= 0) {
                cancelHint(id);
                return;
            }
            if (compassService.playerHasCompass(p)) {
                cancelHint(id);
                return;
            }
            p.sendActionBar(lang.msg("compass.inv-full-actionbar"));
        }, 0L, 20L);
        hintTasks.put(id, task);
    }

    private void cancelHint(UUID id) {
        BukkitTask t = hintTasks.remove(id);
        if (t != null) {
            t.cancel();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        compassService.clearTargetIf(event.getPlayer().getUniqueId());
        cancelHint(event.getPlayer().getUniqueId());
        fullInvHinted.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!compassService.isCompass(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        effects.play(player, EffectService.EffectKey.COMPASS_OPEN_GUI);
        compassGui.open(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        if (compassService.isCompass(item.getItemStack())) {
            event.setCancelled(true);
            item.remove();
            // destroy — also remove from inv already dropped; restore cancel means still in inv
            // Design: destroy entity. Cancelling keeps item; instead allow drop then remove entity:
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropDestroy(PlayerDropItemEvent event) {
        // if another plugin allows, destroy entity
        Item item = event.getItemDrop();
        if (compassService.isCompass(item.getItemStack())) {
            Bukkit.getScheduler().runTask(plugin, item::remove);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropForceDestroy(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        if (!compassService.isCompass(stack)) {
            return;
        }
        // Cancel to prevent pickup race, remove item from player by consuming
        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (compassService.isCompass(hand)) {
            player.getInventory().setItemInMainHand(null);
        } else {
            ItemStack off = player.getInventory().getItemInOffHand();
            if (compassService.isCompass(off)) {
                player.getInventory().setItemInOffHand(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (!compassService.isCompass(stack)) {
            return;
        }
        if (compassService.playerHasCompass(player)) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        compassService.stripCompassFromDrops(event.getDrops());
        // also ensure keepInventory path: remove from inventory drops already handled
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!eventActive()) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !eventActive()) {
                return;
            }
            if (!compassService.playerHasCompass(player)) {
                CompassService.GiveResult r = compassService.tryGive(player);
                if (r == CompassService.GiveResult.INVENTORY_FULL) {
                    startFullInvHint(player);
                }
            }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean movingCompass = compassService.isCompass(current) || compassService.isCompass(cursor)
                || compassService.isCompass(event.getHotbarButton() >= 0
                ? player.getInventory().getItem(event.getHotbarButton()) : null);

        if (!movingCompass) {
            return;
        }

        Inventory clicked = event.getClickedInventory();
        Inventory top = event.getView().getTopInventory();

        // Deny placing into non-player inventories / crafting / armor
        if (clicked != null && !(clicked instanceof PlayerInventory)) {
            event.setCancelled(true);
            return;
        }
        if (event.getSlotType() == InventoryType.SlotType.CRAFTING
                || event.getSlotType() == InventoryType.SlotType.RESULT
                || event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
            return;
        }
        if (top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.PLAYER
                && event.getRawSlot() < top.getSize()) {
            // shift into chest etc
            if (compassService.isCompass(current)) {
                event.setCancelled(true);
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> compassService.enforceSingleCompass(player));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!compassService.isCompass(event.getOldCursor()) && !compassService.isCompass(event.getCursor())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize() && top.getType() != InventoryType.CRAFTING) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
