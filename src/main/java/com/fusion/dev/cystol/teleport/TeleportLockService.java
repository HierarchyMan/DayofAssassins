package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Hunt-phase movement lock: commands, teleports, and world-changes.
 * FFA has no lock (kills are zone-gated instead).
 *
 * <p>Layers:
 * <ol>
 *   <li>Cancel blocked commands</li>
 *   <li>Cancel non-exempt {@link org.bukkit.event.player.PlayerTeleportEvent}s</li>
 *   <li>Revert illegal {@link org.bukkit.event.player.PlayerChangedWorldEvent}s
 *       (world change is not cancellable — snap back to last safe location)</li>
 * </ol>
 *
 * <p><strong>Temp RTP allow</strong> is a short tick window so BetterRTP's own PLUGIN
 * teleport is not cancelled. It does <em>not</em> open {@code /spawn}, COMMAND teleports,
 * or staff bypass. Cleared when the plugin TP lands (or on timeout).
 */
public final class TeleportLockService {

    public static final String BYPASS_PERM = "preciv.teleport.bypass";

    /** Snap-back after illegal world-change only needs a couple of ticks. */
    public static final long REVERT_ALLOW_TICKS = 3L;

    private final EventManager eventManager;
    private final PluginConfig config;
    private final NoBypassService noBypass;
    private final Logger logger;
    /** Temporary allow for plugin-initiated RTP (BetterRTP may finish a few ticks later). */
    private final ConcurrentHashMap<UUID, Long> temporaryAllowUntilMs = new ConcurrentHashMap<>();
    /**
     * Last known feet location while lock matters (join / allowed move / allowed TP).
     * Used to revert illegal world changes.
     */
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();

    public TeleportLockService(EventManager eventManager, PluginConfig config) {
        this(eventManager, config, null, null);
    }

    public TeleportLockService(EventManager eventManager, PluginConfig config, NoBypassService noBypass) {
        this(eventManager, config, noBypass, null);
    }

    public TeleportLockService(
            EventManager eventManager,
            PluginConfig config,
            NoBypassService noBypass,
            Logger logger
    ) {
        this.eventManager = eventManager;
        this.config = config;
        this.noBypass = noBypass;
        this.logger = logger;
    }

    public boolean isLockActive() {
        if (config == null || !config.teleportLockEnabled()) {
            return false;
        }
        if (eventManager == null) {
            return false;
        }
        return eventManager.phase() == EventPhase.HUNT;
    }

    public boolean isDebug() {
        return config != null && config.teleportLockDebug();
    }

    /**
     * Bypass is ignored while {@link NoBypassService} is active.
     */
    public boolean hasBypass(Player player) {
        if (player == null) {
            return false;
        }
        if (noBypass != null && noBypass.isActive()) {
            return false;
        }
        return player.hasPermission(BYPASS_PERM);
    }

    /**
     * Staff bypass only — <em>not</em> temp RTP allow.
     * Temp allow is intentionally narrower (plugin-style teleports only).
     */
    public boolean isFullyExempt(Player player) {
        return hasBypass(player);
    }

    /**
     * Allow plugin-style teleports/world-changes for this player until {@code now + durationMs}.
     * Used so hunt BetterRTP is not cancelled. Prefer {@link #allowTemporarilyTicks}.
     */
    public void allowTemporarily(UUID uuid, long durationMs) {
        if (uuid == null) {
            return;
        }
        long ms = Math.max(50L, durationMs); // at least 1 tick
        long until = System.currentTimeMillis() + ms;
        temporaryAllowUntilMs.merge(uuid, until, Math::max);
    }

    public void allowTemporarily(Player player, long durationMs) {
        if (player != null) {
            allowTemporarily(player.getUniqueId(), durationMs);
        }
    }

    /** Tick-based temp allow (20 ticks = 1s). Preferred for RTP / snap-back windows. */
    public void allowTemporarilyTicks(UUID uuid, long ticks) {
        long t = Math.max(1L, ticks);
        allowTemporarily(uuid, t * 50L);
    }

    public void allowTemporarilyTicks(Player player, long ticks) {
        if (player != null) {
            allowTemporarilyTicks(player.getUniqueId(), ticks);
        }
    }

    public boolean hasTemporaryAllow(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long until = temporaryAllowUntilMs.get(uuid);
        if (until == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= until) {
            temporaryAllowUntilMs.remove(uuid, until);
            return false;
        }
        return true;
    }

    public boolean hasTemporaryAllow(Player player) {
        return player != null && hasTemporaryAllow(player.getUniqueId());
    }

    /** Drop temp allow immediately (e.g. after BetterRTP teleport lands). */
    public void clearTemporaryAllow(UUID uuid) {
        if (uuid != null) {
            temporaryAllowUntilMs.remove(uuid);
        }
    }

    public void clearTemporaryAllow(Player player) {
        if (player != null) {
            clearTemporaryAllow(player.getUniqueId());
        }
    }

    /** Drop expired entries (optional housekeeping). */
    public void pruneExpiredAllows() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : temporaryAllowUntilMs.entrySet()) {
            if (e.getValue() != null && now >= e.getValue()) {
                temporaryAllowUntilMs.remove(e.getKey(), e.getValue());
            }
        }
    }

    // --- last safe location (world-change revert) ---

    /**
     * Remember a location as the last allowed feet position (clone; world must be non-null).
     */
    public void rememberSafeLocation(Player player, Location loc) {
        if (player == null || loc == null || loc.getWorld() == null) {
            return;
        }
        lastSafeLocation.put(player.getUniqueId(), loc.clone());
    }

    public void rememberSafeLocation(Player player) {
        if (player != null) {
            rememberSafeLocation(player, player.getLocation());
        }
    }

    public Location lastSafeLocation(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Location loc = lastSafeLocation.get(uuid);
        return loc == null ? null : loc.clone();
    }

    public Location lastSafeLocation(Player player) {
        return player == null ? null : lastSafeLocation(player.getUniqueId());
    }

    public void clearPlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        lastSafeLocation.remove(uuid);
        temporaryAllowUntilMs.remove(uuid);
    }

    public void clearPlayer(Player player) {
        if (player != null) {
            clearPlayer(player.getUniqueId());
        }
    }

    /**
     * Block command if lock is on, no staff bypass, and label is in the blocked list.
     * <p><strong>Temp RTP allow does not exempt commands</strong> — standing mid-RTP must not
     * open {@code /spawn}, {@code /home}, etc.
     */
    public boolean shouldBlockCommand(Player player, String rawMessage) {
        if (!isLockActive() || hasBypass(player)) {
            return false;
        }
        String label = primaryCommandLabel(rawMessage);
        if (label.isEmpty()) {
            return false;
        }
        return config.teleportLockCommands().contains(label);
    }

    /**
     * Human-readable reason for command allow/block (for debug logs).
     */
    public String explainCommand(Player player, String rawMessage) {
        if (config == null || !config.teleportLockEnabled()) {
            return "allow:teleport-lock.enabled=false";
        }
        EventPhase phase = eventManager != null ? eventManager.phase() : null;
        if (phase != EventPhase.HUNT) {
            return "allow:phase=" + phase + " (lock is hunt-only)";
        }
        if (hasBypass(player)) {
            return "allow:bypass-perm" + nobypassTag();
        }
        String label = primaryCommandLabel(rawMessage);
        if (label.isEmpty()) {
            return "allow:empty-label";
        }
        if (!config.teleportLockCommands().contains(label)) {
            return "allow:label-not-in-blocklist label=" + label;
        }
        // temp allow must NOT open commands — mention if active so operators notice
        if (hasTemporaryAllow(player)) {
            return "block:label=" + label + " (temp-rtp-allow active but commands still locked)";
        }
        return "block:label=" + label;
    }

    /**
     * Block a teleport when lock is on, not staff-bypassed, cause is not natural combat/portal,
     * and the move is not a same-world hop that stays inside free zones.
     *
     * <p>Temp RTP allow only passes <em>plugin-style</em> causes ({@code PLUGIN}/{@code UNKNOWN}),
     * never {@code COMMAND} — so Essentials-style command TPs stay blocked even mid-RTP.
     *
     * <p>Cross-world teleports are always blocked (unless staff bypass / plugin-style temp allow).
     */
    public boolean shouldBlockTeleport(Player player, Location from, Location to, TeleportCause cause) {
        if (!isLockActive() || hasBypass(player)) {
            return false;
        }
        if (isExemptCause(cause)) {
            return false;
        }
        // Short BetterRTP window: only plugin-style TPs (not /spawn COMMAND)
        if (hasTemporaryAllow(player) && isPluginStyleCause(cause)) {
            return false;
        }
        if (from == null) {
            return true;
        }
        // Cross-world = always block (command/plugin hub TP, Multiverse, etc.)
        if (isCrossWorld(from, to)) {
            return true;
        }
        // Same world: free movement only while already standing in spawn/arena free zone
        // AND destination also free zone (no warping out of the safe cuboid via plugin TP).
        if (isInAllowedZone(from) && to != null && isInAllowedZone(to)) {
            return false;
        }
        if (isInAllowedZone(from) && to == null) {
            // unknown dest — allow only if from free (legacy path)
            return false;
        }
        // Same-world plugin TP from outside free zones → block
        // Same-world plugin TP from free zone to outside → block (no escape via /warp)
        return true;
    }

    public String explainTeleport(Player player, Location from, Location to, TeleportCause cause) {
        if (config == null || !config.teleportLockEnabled()) {
            return "allow:teleport-lock.enabled=false";
        }
        EventPhase phase = eventManager != null ? eventManager.phase() : null;
        if (phase != EventPhase.HUNT) {
            return "allow:phase=" + phase;
        }
        if (hasBypass(player)) {
            return "allow:bypass-perm" + nobypassTag();
        }
        if (isExemptCause(cause)) {
            return "allow:exempt-cause=" + cause;
        }
        if (hasTemporaryAllow(player) && isPluginStyleCause(cause)) {
            return "allow:temp-rtp-plugin-tp cause=" + cause;
        }
        if (hasTemporaryAllow(player) && !isPluginStyleCause(cause)) {
            return "block:temp-rtp-does-not-cover cause=" + cause;
        }
        if (from == null) {
            return "block:from-null";
        }
        if (isCrossWorld(from, to)) {
            return "block:cross-world from=" + worldName(from) + " to=" + worldName(to);
        }
        boolean fromOk = isInAllowedZone(from);
        boolean toOk = to != null && isInAllowedZone(to);
        if (fromOk && to != null && toOk) {
            return "allow:same-world free-zone hop";
        }
        if (fromOk && to == null) {
            return "allow:from-free to-unknown (legacy)";
        }
        return "block:same-world fromFree=" + fromOk + " toFree=" + toOk + " cause=" + cause;
    }

    /**
     * Backward-compatible overload (from-only). Prefer from+to overload.
     */
    public boolean shouldBlockTeleport(Player player, Location from, TeleportCause cause) {
        return shouldBlockTeleport(player, from, null, cause);
    }

    /**
     * World change cannot be cancelled — caller must revert when this returns true.
     *
     * <p>If the preceding {@link org.bukkit.event.player.PlayerTeleportEvent} was allowed
     * (e.g. nether portal or plugin RTP), {@link #rememberSafeLocation} already stored the
     * destination in the new world — do not revert. Illegal hub {@code /spawn} either had no
     * allowed TP (last safe still in old world) or never fired a TP event → revert.
     */
    public boolean shouldBlockWorldChange(Player player, World fromWorld) {
        if (player == null) {
            return false;
        }
        if (!isLockActive() || hasBypass(player)) {
            return false;
        }
        // BetterRTP / snap-back mid-flight may change world; do not snap while allow is open
        if (hasTemporaryAllow(player)) {
            return false;
        }
        Location last = lastSafeLocation.get(player.getUniqueId());
        World current = player.getWorld();
        // Allowed cross-world TP already adopted dest as last safe → stay
        if (last != null && last.getWorld() != null && current != null
                && last.getWorld().getUID().equals(current.getUID())) {
            return false;
        }
        // last safe still in previous world (or unknown) → illegal world change
        return true;
    }

    public String explainWorldChange(Player player, World fromWorld) {
        if (player == null) {
            return "allow:null-player";
        }
        if (config == null || !config.teleportLockEnabled()) {
            return "allow:teleport-lock.enabled=false";
        }
        EventPhase phase = eventManager != null ? eventManager.phase() : null;
        if (phase != EventPhase.HUNT) {
            return "allow:phase=" + phase;
        }
        if (hasBypass(player)) {
            return "allow:bypass-perm" + nobypassTag();
        }
        if (hasTemporaryAllow(player)) {
            return "allow:temp-rtp-window";
        }
        Location last = lastSafeLocation.get(player.getUniqueId());
        World current = player.getWorld();
        String lastW = last != null && last.getWorld() != null ? last.getWorld().getName() : "null";
        String curW = current != null ? current.getName() : "null";
        String fromW = fromWorld != null ? fromWorld.getName() : "null";
        if (last != null && last.getWorld() != null && current != null
                && last.getWorld().getUID().equals(current.getUID())) {
            return "allow:last-safe-already-in-dest last=" + lastW + " current=" + curW;
        }
        return "block:revert from=" + fromW + " current=" + curW + " lastSafe=" + lastW;
    }

    private String nobypassTag() {
        if (noBypass != null && noBypass.isActive()) {
            return " (nobypass ON — unexpected if you see bypass)";
        }
        return "";
    }

    private static String worldName(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "null";
        }
        return loc.getWorld().getName();
    }

    public void debug(String message) {
        if (!isDebug() || logger == null || message == null) {
            return;
        }
        logger.info("[tp-lock] " + message);
    }

    /**
     * Pure gate for tests: whether a world change should be reverted.
     *
     * @param lastSafeInCurrentWorld true if last remembered location is already in the new world
     */
    public static boolean shouldBlockWorldChangePure(
            boolean lockActive,
            boolean fullyExempt,
            boolean lastSafeInCurrentWorld
    ) {
        if (!lockActive || fullyExempt) {
            return false;
        }
        return !lastSafeInCurrentWorld;
    }

    /**
     * Pure gate for tests: cross-world + same-world free-zone rules without Bukkit player.
     * {@code tempPluginAllow} = short RTP window covering plugin-style causes only.
     */
    public static boolean shouldBlockTeleportPure(
            boolean lockActive,
            boolean fullyExempt,
            boolean exemptCause,
            boolean crossWorld,
            boolean fromAllowed,
            boolean toAllowed,
            boolean toKnown
    ) {
        return shouldBlockTeleportPure(
                lockActive, fullyExempt, exemptCause, false, false,
                crossWorld, fromAllowed, toAllowed, toKnown
        );
    }

    /**
     * @param tempPluginAllow temp RTP allow is active
     * @param pluginStyleCause cause is PLUGIN/UNKNOWN (not COMMAND)
     */
    public static boolean shouldBlockTeleportPure(
            boolean lockActive,
            boolean fullyExempt,
            boolean exemptCause,
            boolean tempPluginAllow,
            boolean pluginStyleCause,
            boolean crossWorld,
            boolean fromAllowed,
            boolean toAllowed,
            boolean toKnown
    ) {
        if (!lockActive || fullyExempt || exemptCause) {
            return false;
        }
        if (tempPluginAllow && pluginStyleCause) {
            return false;
        }
        if (crossWorld) {
            return true;
        }
        if (fromAllowed && (!toKnown || toAllowed)) {
            return false;
        }
        return true;
    }

    public static boolean isCrossWorld(Location from, Location to) {
        if (from == null || from.getWorld() == null) {
            return false;
        }
        if (to == null || to.getWorld() == null) {
            return false;
        }
        return !from.getWorld().getUID().equals(to.getWorld().getUID());
    }

    public boolean isInAllowedZone(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        return isInArena(loc) || isInSpawn(loc);
    }

    public boolean isInArena(Location loc) {
        if (config == null) {
            return false;
        }
        return inConfiguredCuboid(loc, config.arenaWorld(), config.arenaCuboid());
    }

    public boolean isInSpawn(Location loc) {
        if (config == null || !config.spawnZoneConfigured()) {
            return false;
        }
        return inConfiguredCuboid(loc, config.spawnWorld(), config.spawnCuboid());
    }

    private static boolean inConfiguredCuboid(Location loc, String worldName, CuboidBounds cuboid) {
        if (loc == null || loc.getWorld() == null || worldName == null || cuboid == null) {
            return false;
        }
        if (!loc.getWorld().getName().equalsIgnoreCase(worldName)) {
            return false;
        }
        return cuboid.contains(loc.getX(), loc.getY(), loc.getZ());
    }

    /**
     * Causes that are natural movement, not “warp/home/plugin TP” style.
     * Portals stay exempt so nether/end exploration works; hub plugins use PLUGIN/COMMAND/UNKNOWN.
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
     * Causes covered by the short temp RTP allow (BetterRTP / our own snap-back).
     * Does <em>not</em> include {@link TeleportCause#COMMAND}.
     */
    public static boolean isPluginStyleCause(TeleportCause cause) {
        if (cause == null) {
            return true;
        }
        return cause == TeleportCause.PLUGIN || cause == TeleportCause.UNKNOWN;
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
