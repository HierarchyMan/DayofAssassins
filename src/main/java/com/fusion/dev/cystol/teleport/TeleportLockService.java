package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Locale;
import java.util.Set;

/**
 * Hunt-phase teleport lock. FFA has no lock (kills are zone-gated instead).
 */
public final class TeleportLockService {

    public static final String BYPASS_PERM = "preciv.teleport.bypass";

    private final EventManager eventManager;
    private final PluginConfig config;

    public TeleportLockService(EventManager eventManager, PluginConfig config) {
        this.eventManager = eventManager;
        this.config = config;
    }

    public boolean isLockActive() {
        if (!config.teleportLockEnabled()) {
            return false;
        }
        return eventManager.phase() == EventPhase.HUNT;
    }

    public boolean hasBypass(Player player) {
        return player != null && player.hasPermission(BYPASS_PERM);
    }

    /**
     * Block command if lock is on, no bypass, and label is in the blocked list.
     * Zone is not checked here — players in spawn/arena still should not fire warp/home
     * from those zones if we only care about destination… Spec: free TP while <em>in</em>
     * spawn or arena (from-location). So if they are in an allowed zone, do not block.
     */
    public boolean shouldBlockCommand(Player player, String rawMessage) {
        if (!isLockActive() || hasBypass(player)) {
            return false;
        }
        if (isInAllowedZone(player.getLocation())) {
            return false;
        }
        String label = primaryCommandLabel(rawMessage);
        if (label.isEmpty()) {
            return false;
        }
        return config.teleportLockCommands().contains(label);
    }

    public boolean shouldBlockTeleport(Player player, Location from, TeleportCause cause) {
        if (!isLockActive() || hasBypass(player)) {
            return false;
        }
        if (isExemptCause(cause)) {
            return false;
        }
        return !isInAllowedZone(from);
    }

    public boolean isInAllowedZone(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        return isInArena(loc) || isInSpawn(loc);
    }

    public boolean isInArena(Location loc) {
        return inConfiguredCuboid(loc, config.arenaWorld(), config.arenaCuboid());
    }

    public boolean isInSpawn(Location loc) {
        if (!config.spawnZoneConfigured()) {
            return false;
        }
        return inConfiguredCuboid(loc, config.spawnWorld(), config.spawnCuboid());
    }

    private static boolean inConfiguredCuboid(Location loc, String worldName, CuboidBounds cuboid) {
        if (loc == null || loc.getWorld() == null || worldName == null || cuboid == null) {
            return false;
        }
        if (!loc.getWorld().getName().equals(worldName)) {
            return false;
        }
        return cuboid.contains(loc.getX(), loc.getY(), loc.getZ());
    }

    /**
     * Causes that are not “warp/home/plugin TP” style movement.
     */
    public static boolean isExemptCause(TeleportCause cause) {
        if (cause == null) {
            return false;
        }
        return switch (cause) {
            case ENDER_PEARL, CHORUS_FRUIT, NETHER_PORTAL, END_PORTAL, END_GATEWAY,
                 SPECTATE, EXIT_BED, DISMOUNT -> true;
            default -> false;
        };
    }

    /**
     * {@code /essentials:spawn foo} → {@code spawn}
     */
    public static String primaryCommandLabel(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "";
        }
        String s = rawMessage.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        int space = s.indexOf(' ');
        if (space >= 0) {
            s = s.substring(0, space);
        }
        int colon = s.indexOf(':');
        if (colon >= 0 && colon < s.length() - 1) {
            s = s.substring(colon + 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** Package-visible for tests: blocked set contains label. */
    public static boolean isBlockedLabel(String label, Set<String> blocked) {
        if (label == null || label.isEmpty() || blocked == null) {
            return false;
        }
        return blocked.contains(label.toLowerCase(Locale.ROOT));
    }
}
