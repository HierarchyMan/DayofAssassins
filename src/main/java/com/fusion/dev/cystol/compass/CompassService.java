package com.fusion.dev.cystol.compass;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CompassService {

    private final PluginConfig config;
    private final Lang lang;
    private final CompassKeys keys;
    private final ConcurrentHashMap<UUID, UUID> targets = new ConcurrentHashMap<>();

    public CompassService(PluginConfig config, Lang lang, CompassKeys keys) {
        this.config = config;
        this.lang = lang;
        this.keys = keys;
    }

    public boolean isCompass(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(keys.compassItem, PersistentDataType.BYTE);
    }

    public boolean isWand(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(keys.wandItem, PersistentDataType.BYTE);
    }

    public boolean playerHasCompass(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isCompass(stack)) {
                return true;
            }
        }
        if (isCompass(player.getInventory().getItemInOffHand())) {
            return true;
        }
        return isCompass(player.getItemOnCursor());
    }

    public ItemStack createCompass(Player owner) {
        Material mat;
        try {
            mat = Material.valueOf(config.compassMaterial().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            mat = Material.COMPASS;
        }
        ItemStack stack = new ItemStack(mat);
        applyStateMeta(stack, owner);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(keys.compassItem, PersistentDataType.BYTE, (byte) 1);
        if (config.compassCmd() > 0) {
            meta.setCustomModelData(config.compassCmd());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack createWand() {
        Material mat;
        try {
            mat = Material.valueOf(config.wandMaterial().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            mat = Material.BLAZE_ROD;
        }
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.component(lang.raw("wand.name"), Map.of()));
        meta.lore(TextUtil.componentList(lang.rawList("wand.lore"), Map.of()));
        meta.getPersistentDataContainer().set(keys.wandItem, PersistentDataType.BYTE, (byte) 1);
        if (config.wandCmd() > 0) {
            meta.setCustomModelData(config.wandCmd());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public Optional<UUID> getTarget(Player player) {
        return Optional.ofNullable(targets.get(player.getUniqueId()));
    }

    public void setTarget(Player player, UUID target) {
        if (target == null) {
            targets.remove(player.getUniqueId());
        } else {
            targets.put(player.getUniqueId(), target);
        }
        refreshCompassItems(player);
    }

    public void clearTarget(Player player) {
        targets.remove(player.getUniqueId());
        refreshCompassItems(player);
    }

    public void clearTargetIf(UUID targetId) {
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            if (targetId.equals(e.getValue())) {
                targets.remove(e.getKey());
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    refreshCompassItems(p);
                }
            }
        }
    }

    public GiveResult tryGive(Player player) {
        if (playerHasCompass(player)) {
            return GiveResult.ALREADY_HAS;
        }
        ItemStack compass = createCompass(player);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(compass);
        if (!leftover.isEmpty()) {
            return GiveResult.INVENTORY_FULL;
        }
        return GiveResult.GIVEN;
    }

    public enum GiveResult {
        GIVEN, ALREADY_HAS, INVENTORY_FULL
    }

    public void refreshCompassItems(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isCompass(stack)) {
                applyStateMeta(stack, player);
                player.getInventory().setItem(i, stack);
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isCompass(off)) {
            applyStateMeta(off, player);
            player.getInventory().setItemInOffHand(off);
        }
    }

    public void applyStateMeta(ItemStack stack, Player owner) {
        if (stack == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        UUID targetId = targets.get(owner.getUniqueId());
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        String stateKey;
        Map<String, String> ph = new HashMap<>();
        if (targetId == null) {
            stateKey = "compass.item.inactive";
        } else if (target == null || !target.isOnline()) {
            stateKey = "compass.item.lost-target";
            targets.remove(owner.getUniqueId());
        } else if (!target.getWorld().equals(owner.getWorld())) {
            stateKey = "compass.item.tracking-other-world";
            ph.put("target", target.getName());
            ph.put("world", target.getWorld().getName());
        } else {
            stateKey = "compass.item.tracking";
            ph.put("target", target.getName());
            ph.put("world", target.getWorld().getName());
        }
        meta.displayName(TextUtil.component(lang.raw(stateKey + ".name"), ph));
        meta.lore(TextUtil.componentList(lang.rawList(stateKey + ".lore"), ph));
        meta.getPersistentDataContainer().set(keys.compassItem, PersistentDataType.BYTE, (byte) 1);
        if (meta instanceof CompassMeta compassMeta && target != null && target.getWorld().equals(owner.getWorld())) {
            compassMeta.setLodestone(target.getLocation());
            compassMeta.setLodestoneTracked(false);
        } else if (meta instanceof CompassMeta compassMeta) {
            compassMeta.setLodestone(null);
            compassMeta.setLodestoneTracked(false);
        }
        stack.setItemMeta(meta);
    }

    public void tickTracking(Player player) {
        UUID targetId = targets.get(player.getUniqueId());
        if (targetId == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline() || target.getGameMode() == GameMode.SPECTATOR) {
            clearTarget(player);
            return;
        }
        // refresh lodestone lodestone
        for (ItemStack stack : player.getInventory().getContents()) {
            if (!isCompass(stack)) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof CompassMeta compassMeta) {
                if (target.getWorld().equals(player.getWorld())) {
                    compassMeta.setLodestone(target.getLocation());
                    compassMeta.setLodestoneTracked(false);
                } else {
                    compassMeta.setLodestone(null);
                    player.sendActionBar(TextUtil.component(
                            lang.raw("compass.item.tracking-other-world.lore").contains("%world%")
                                    ? "&eTarget is in: &f%world%"
                                    : "&eTarget is in: &f%world%",
                            Map.of("world", target.getWorld().getName())));
                }
                stack.setItemMeta(compassMeta);
            }
        }
        // action bar other dimension
        if (!target.getWorld().equals(player.getWorld())) {
            player.sendActionBar(TextUtil.component("&eTarget is in: &f%world%",
                    Map.of("world", target.getWorld().getName())));
        }
    }

    public void stripCompassFromDrops(java.util.List<ItemStack> drops) {
        drops.removeIf(this::isCompass);
    }

    public int countCompasses(Player player) {
        int n = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isCompass(stack)) {
                n += stack.getAmount();
            }
        }
        if (isCompass(player.getInventory().getItemInOffHand())) {
            n += player.getInventory().getItemInOffHand().getAmount();
        }
        return n;
    }

    public void enforceSingleCompass(Player player) {
        boolean seen = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (!isCompass(stack)) {
                continue;
            }
            if (!seen) {
                if (stack.getAmount() > 1) {
                    stack.setAmount(1);
                }
                seen = true;
            } else {
                player.getInventory().setItem(i, null);
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isCompass(off)) {
            if (seen) {
                player.getInventory().setItemInOffHand(null);
            } else if (off.getAmount() > 1) {
                off.setAmount(1);
            }
        }
    }
}
