package com.fusion.dev.cystol.compass;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CompassService {

    /** Squared blocks — skip lodestone rewrite if target moved less than this. */
    private static final double MOVE_THRESHOLD_SQ = 2.25; // 1.5 blocks

    private final PluginConfig config;
    private final Lang lang;
    private final CompassKeys keys;
    private final ConcurrentHashMap<UUID, UUID> targets = new ConcurrentHashMap<>();
    /** Last lodestone snapshot per hunter: world name + block coords. */
    private final ConcurrentHashMap<UUID, LodestoneSnap> lastLodestone = new ConcurrentHashMap<>();

    private record LodestoneSnap(String world, int x, int y, int z, boolean sameWorld) {
    }

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

    public boolean isSpawnWand(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(keys.spawnWandItem, PersistentDataType.BYTE);
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

    public ItemStack createSpawnWand() {
        Material mat;
        try {
            mat = Material.valueOf(config.spawnWandMaterial().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            mat = Material.BLAZE_ROD;
        }
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.component(lang.raw("spawn-wand.name"), Map.of()));
        meta.lore(TextUtil.componentList(lang.rawList("spawn-wand.lore"), Map.of()));
        meta.getPersistentDataContainer().set(keys.spawnWandItem, PersistentDataType.BYTE, (byte) 1);
        if (config.spawnWandCmd() > 0) {
            meta.setCustomModelData(config.spawnWandCmd());
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
            lastLodestone.remove(player.getUniqueId());
        } else {
            targets.put(player.getUniqueId(), target);
            lastLodestone.remove(player.getUniqueId());
        }
        refreshCompassItems(player);
    }

    public void clearTarget(Player player) {
        targets.remove(player.getUniqueId());
        lastLodestone.remove(player.getUniqueId());
        refreshCompassItems(player);
    }

    public void clearTargetIf(UUID targetId) {
        List<UUID> hunters = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            if (targetId.equals(e.getValue())) {
                hunters.add(e.getKey());
            }
        }
        for (UUID hunterId : hunters) {
            targets.remove(hunterId);
            lastLodestone.remove(hunterId);
            Player p = Bukkit.getPlayer(hunterId);
            if (p != null) {
                refreshCompassItems(p);
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
            lastLodestone.remove(owner.getUniqueId());
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
            Location loc = target.getLocation();
            compassMeta.setLodestone(loc);
            compassMeta.setLodestoneTracked(false);
            lastLodestone.put(owner.getUniqueId(), snapOf(loc, true));
        } else if (meta instanceof CompassMeta compassMeta) {
            compassMeta.setLodestone(null);
            compassMeta.setLodestoneTracked(false);
            lastLodestone.put(owner.getUniqueId(), new LodestoneSnap(null, 0, 0, 0, false));
        }
        stack.setItemMeta(meta);
    }

    /**
     * Tick only hunters who currently have a target (not all online players).
     */
    public void tickAllTrackers() {
        Set<UUID> hunters = Set.copyOf(targets.keySet());
        for (UUID hunterId : hunters) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null && hunter.isOnline()) {
                tickTracking(hunter);
            }
        }
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
        boolean sameWorld = target.getWorld().equals(player.getWorld());
        Location targetLoc = target.getLocation();
        LodestoneSnap prev = lastLodestone.get(player.getUniqueId());
        if (prev != null && !needsLodestoneUpdate(prev, targetLoc, sameWorld)) {
            if (!sameWorld) {
                player.sendActionBar(lang.msg("compass.other-world-actionbar",
                        Map.of("world", target.getWorld().getName())));
            }
            return;
        }

        applyLodestoneToCompasses(player, sameWorld ? targetLoc : null, sameWorld);
        lastLodestone.put(player.getUniqueId(), sameWorld
                ? snapOf(targetLoc, true)
                : new LodestoneSnap(target.getWorld().getName(), 0, 0, 0, false));

        if (!sameWorld) {
            player.sendActionBar(lang.msg("compass.other-world-actionbar",
                    Map.of("world", target.getWorld().getName())));
        }
    }

    private static boolean needsLodestoneUpdate(LodestoneSnap prev, Location targetLoc, boolean sameWorld) {
        if (prev.sameWorld() != sameWorld) {
            return true;
        }
        if (!sameWorld) {
            // other-world: lodestone already cleared; no meta rewrite needed each tick
            return false;
        }
        World w = targetLoc.getWorld();
        if (w == null || prev.world() == null || !prev.world().equals(w.getName())) {
            return true;
        }
        int bx = targetLoc.getBlockX();
        int by = targetLoc.getBlockY();
        int bz = targetLoc.getBlockZ();
        double dx = bx - prev.x();
        double dy = by - prev.y();
        double dz = bz - prev.z();
        return (dx * dx + dy * dy + dz * dz) >= MOVE_THRESHOLD_SQ;
    }

    private static LodestoneSnap snapOf(Location loc, boolean sameWorld) {
        World w = loc.getWorld();
        return new LodestoneSnap(
                w == null ? null : w.getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                sameWorld
        );
    }

    private void applyLodestoneToCompasses(Player player, Location lodestone, boolean sameWorld) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isCompass(stack)) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof CompassMeta compassMeta) {
                if (sameWorld && lodestone != null) {
                    compassMeta.setLodestone(lodestone);
                    compassMeta.setLodestoneTracked(false);
                } else {
                    compassMeta.setLodestone(null);
                    compassMeta.setLodestoneTracked(false);
                }
                stack.setItemMeta(compassMeta);
                player.getInventory().setItem(i, stack);
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isCompass(off)) {
            ItemMeta meta = off.getItemMeta();
            if (meta instanceof CompassMeta compassMeta) {
                if (sameWorld && lodestone != null) {
                    compassMeta.setLodestone(lodestone);
                    compassMeta.setLodestoneTracked(false);
                } else {
                    compassMeta.setLodestone(null);
                    compassMeta.setLodestoneTracked(false);
                }
                off.setItemMeta(compassMeta);
                player.getInventory().setItemInOffHand(off);
            }
        }
    }

    public void stripCompassFromDrops(java.util.List<ItemStack> drops) {
        drops.removeIf(this::isCompass);
    }

    /** Remove every plugin compass from a player and clear their track target. Silent. */
    public void stripFromPlayer(Player player) {
        if (player == null) {
            return;
        }
        targets.remove(player.getUniqueId());
        lastLodestone.remove(player.getUniqueId());
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isCompass(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
        if (isCompass(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
        if (isCompass(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    public void stripFromAllOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            stripFromPlayer(p);
        }
        targets.clear();
        lastLodestone.clear();
    }

    public void clearAllTargets() {
        targets.clear();
        lastLodestone.clear();
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
