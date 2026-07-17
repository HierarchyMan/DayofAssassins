package com.fusion.dev.cystol.arena;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.util.VanishService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class FfaSpawnService {

    /** Players teleported per server tick to avoid one-frame freezes. */
    public static final int DEFAULT_BATCH_SIZE = 10;

    private final PluginConfig config;
    private final VanishService vanishService;
    private final Logger logger;

    public FfaSpawnService(PluginConfig config, VanishService vanishService, Logger logger) {
        this.config = config;
        this.vanishService = vanishService;
        this.logger = logger;
    }

    public List<Player> eligiblePlayers() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL) {
                continue;
            }
            if (p.hasPermission("preciv.ffa.tp.bypass")) {
                continue;
            }
            if (vanishService.isVanished(p)) {
                continue;
            }
            list.add(p);
        }
        return list;
    }

    public List<Location> buildSpawnLocations(int count) {
        World world = Bukkit.getWorld(config.arenaWorld());
        if (world == null) {
            logger.warning("Arena world not loaded: " + config.arenaWorld());
            return List.of();
        }
        CuboidBounds cuboid = config.arenaCuboid();
        double cx = config.centerX();
        double cy = config.centerY();
        double cz = config.centerZ();
        double margin = config.ringMarginBlocks();
        double frac = config.maxDiameterFraction();
        double spacing = config.minPlayerSpacing();
        int air = config.minAirAbove();
        int yRange = config.ySearchRange();

        Random rng = ThreadLocalRandom.current();
        List<RingSpawnMath.SpawnPoint> points = RingSpawnMath.computeSpawns(
                cuboid, cx, cy, cz, count, margin, frac, spacing,
                xz -> cuboid.containsHorizontal(xz[0], xz[1]),
                rng
        );

        // Touch chunks once per unique column before Y probes.
        for (RingSpawnMath.SpawnPoint sp : points) {
            int bx = (int) Math.floor(sp.x());
            int bz = (int) Math.floor(sp.z());
            world.getChunkAt(bx >> 4, bz >> 4);
        }

        List<Location> locations = new ArrayList<>(points.size());
        for (RingSpawnMath.SpawnPoint sp : points) {
            SafeColumn col = resolveSafeColumn(world, sp.x(), sp.z(), cy, cuboid, yRange, air, cx, cz, rng);
            if (col == null) {
                logger.warning(String.format(
                        "No dry standable column for planned spawn (%.1f, %.1f) — refusing water/air feet",
                        sp.x(), sp.z()
                ));
                continue;
            }
            locations.add(new Location(world, col.x, col.y, col.z, sp.yaw(), 0f));
        }
        // Keep player↔location pairing possible when a few columns failed.
        if (!locations.isEmpty() && locations.size() < count) {
            Location pad = locations.getLast();
            while (locations.size() < count) {
                locations.add(pad.clone());
            }
        }
        return locations;
    }

    private record SafeColumn(double x, int y, double z) {
    }

    /**
     * Resolve a dry standable column for a planned XZ: exact column first, then nearby nudges,
     * then center, then random cuboid samples. Never returns water/air/lava feet.
     */
    private SafeColumn resolveSafeColumn(
            World world,
            double x,
            double z,
            double preferredY,
            CuboidBounds cuboid,
            int ySearchRange,
            int minAirAbove,
            double centerX,
            double centerZ,
            Random rng
    ) {
        OptionalInt exact = findStandableY(world, x, z, preferredY, cuboid, ySearchRange, minAirAbove);
        if (exact.isPresent()) {
            return new SafeColumn(x, exact.getAsInt(), z);
        }
        // Nearby ring around planned point
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = 1.0 + rng.nextDouble() * 4.0;
            double nx = clamp(x + Math.cos(angle) * dist, cuboid.minX(), cuboid.maxX());
            double nz = clamp(z + Math.sin(angle) * dist, cuboid.minZ(), cuboid.maxZ());
            OptionalInt y = findStandableY(world, nx, nz, preferredY, cuboid, ySearchRange, minAirAbove);
            if (y.isPresent()) {
                return new SafeColumn(nx, y.getAsInt(), nz);
            }
        }
        // Arena center
        OptionalInt centerY = findStandableYFull(world, centerX, centerZ, preferredY, cuboid, minAirAbove);
        if (centerY.isPresent()) {
            return new SafeColumn(centerX, centerY.getAsInt(), centerZ);
        }
        // Random columns inside cuboid
        for (int attempt = 0; attempt < 32; attempt++) {
            double spanX = Math.max(1e-3, cuboid.maxX() - cuboid.minX());
            double spanZ = Math.max(1e-3, cuboid.maxZ() - cuboid.minZ());
            double nx = cuboid.minX() + rng.nextDouble() * spanX;
            double nz = cuboid.minZ() + rng.nextDouble() * spanZ;
            OptionalInt y = findStandableYFull(world, nx, nz, preferredY, cuboid, minAirAbove);
            if (y.isPresent()) {
                return new SafeColumn(nx, y.getAsInt(), nz);
            }
        }
        return null;
    }

    /**
     * Prefer Y closest to centerY within search range; require solid dry floor and clear air column.
     * Feet Y is the block coordinate the player stands in (floor at y-1).
     * Empty if no safe Y — callers must not invent water/air feet positions.
     */
    public OptionalInt findStandableY(World world, double x, double z, double preferredY,
                                     CuboidBounds cuboid, int ySearchRange, int minAirAbove) {
        int base = (int) Math.floor(preferredY);
        int air = Math.max(1, minAirAbove);
        int minY = Math.max((int) Math.floor(cuboid.minY()), world.getMinHeight() + 1);
        int maxByWorld = world.getMaxHeight() - air;
        int maxByCuboid = (int) Math.floor(cuboid.maxY()) - (air - 1);
        int maxY = Math.min(maxByCuboid, maxByWorld);
        if (maxY < minY) {
            return OptionalInt.empty();
        }

        int bestY = -1;
        int bestDist = Integer.MAX_VALUE;
        int range = Math.max(0, ySearchRange);
        for (int d = 0; d <= range; d++) {
            for (int sign : d == 0 ? new int[]{0} : new int[]{1, -1}) {
                int y = base + sign * d;
                if (y < minY || y > maxY) {
                    continue;
                }
                if (isSafe(world, x, y, z, air)) {
                    int dist = Math.abs(y - base);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestY = y;
                        if (dist == 0) {
                            return OptionalInt.of(bestY);
                        }
                    }
                }
            }
        }
        if (bestDist != Integer.MAX_VALUE) {
            return OptionalInt.of(bestY);
        }
        // Local range failed — scan entire cuboid height at this column before giving up.
        return findStandableYFull(world, x, z, preferredY, cuboid, air);
    }

    /** Full vertical scan of the column inside the cuboid (still dry + solid only). */
    public OptionalInt findStandableYFull(World world, double x, double z, double preferredY,
                                         CuboidBounds cuboid, int minAirAbove) {
        int air = Math.max(1, minAirAbove);
        int minY = Math.max((int) Math.floor(cuboid.minY()), world.getMinHeight() + 1);
        int maxByWorld = world.getMaxHeight() - air;
        int maxByCuboid = (int) Math.floor(cuboid.maxY()) - (air - 1);
        int maxY = Math.min(maxByCuboid, maxByWorld);
        if (maxY < minY) {
            return OptionalInt.empty();
        }
        int base = (int) Math.floor(preferredY);
        int bestY = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int y = minY; y <= maxY; y++) {
            if (!isSafe(world, x, y, z, air)) {
                continue;
            }
            int dist = Math.abs(y - base);
            if (dist < bestDist) {
                bestDist = dist;
                bestY = y;
            }
        }
        return bestY < 0 ? OptionalInt.empty() : OptionalInt.of(bestY);
    }

    private static double clamp(double v, double lo, double hi) {
        if (lo > hi) {
            return (lo + hi) / 2.0;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Solid dry floor at y-1; feet + head column passable and non-liquid
     * for {@code minAirAbove} blocks. Rejects water, lava, waterlogged spaces, bubble columns.
     */
    boolean isSafe(World world, double x, int y, double z, int minAirAbove) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Block floor = world.getBlockAt(bx, y - 1, bz);
        if (!floor.getType().isSolid() || isLiquidLike(floor)) {
            return false;
        }
        // Magma / campfire / etc. are solid but hostile — still legal stand; water is the hard fail.
        for (int i = 0; i < minAirAbove; i++) {
            Block space = world.getBlockAt(bx, y + i, bz);
            if (isLiquidLike(space)) {
                return false;
            }
            if (!space.isPassable()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLiquidLike(Block block) {
        if (block == null) {
            return true;
        }
        if (block.isLiquid()) {
            return true;
        }
        Material type = block.getType();
        if (type == Material.BUBBLE_COLUMN
                || type == Material.KELP
                || type == Material.KELP_PLANT
                || type == Material.SEAGRASS
                || type == Material.TALL_SEAGRASS
                || type == Material.WATER_CAULDRON
                || type == Material.LAVA_CAULDRON) {
            return true;
        }
        try {
            if (block.getBlockData() instanceof Waterlogged w && w.isWaterlogged()) {
                return true;
            }
        } catch (Throwable ignored) {
            // older / unexpected block data
        }
        return false;
    }

    /**
     * Synchronous teleport (tests / small lists). Prefer {@link #teleportPlayersBatched}.
     */
    public void teleportPlayers(List<Player> players) {
        if (players.isEmpty()) {
            return;
        }
        List<Location> locs = buildSpawnLocations(players.size());
        for (int i = 0; i < players.size(); i++) {
            Location loc = i < locs.size() ? locs.get(i) : locs.isEmpty() ? null : locs.get(0);
            if (loc == null) {
                continue;
            }
            players.get(i).teleport(loc);
        }
    }

    /**
     * Plan safe points once, then teleport in batches across ticks so large FFAs
     * do not freeze a single frame. {@code afterEach} runs on the main thread per player;
     * {@code onComplete} once all batches finish.
     */
    /**
     * Whether the arena world is loaded and spawn math can produce locations.
     * Used to avoid marking FFA teleported when TP cannot run.
     */
    public boolean canBuildSpawns(int playerCount) {
        if (playerCount <= 0) {
            return true;
        }
        World world = Bukkit.getWorld(config.arenaWorld());
        if (world == null) {
            return false;
        }
        return !buildSpawnLocations(playerCount).isEmpty();
    }

    public BukkitTask teleportPlayersBatched(
            JavaPlugin plugin,
            List<Player> players,
            int batchSize,
            Consumer<Player> afterEach,
            Runnable onComplete
    ) {
        if (players.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return null;
        }
        int batch = Math.max(1, batchSize);
        List<Player> ordered = new ArrayList<>(players);
        List<Location> locs = buildSpawnLocations(ordered.size());
        if (locs.isEmpty()) {
            // Caller must treat null return as failure — do not run onComplete (would look like success).
            logger.warning("FFA batch aborted: no spawn locations (world unloaded or cuboid unusable)");
            return null;
        }
        final int[] index = {0};
        final BukkitTask[] holder = new BukkitTask[1];
        final boolean[] completed = {false};
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (completed[0]) {
                return;
            }
            boolean done = false;
            try {
                int start = index[0];
                int end = Math.min(start + batch, ordered.size());
                for (int i = start; i < end; i++) {
                    try {
                        Player p = ordered.get(i);
                        if (p == null || !p.isOnline()) {
                            continue;
                        }
                        Location loc = i < locs.size() ? locs.get(i) : locs.get(0);
                        if (loc == null) {
                            continue;
                        }
                        p.teleport(loc);
                        if (afterEach != null) {
                            afterEach.accept(p);
                        }
                    } catch (RuntimeException perPlayer) {
                        logger.warning("FFA teleport skipped a player: " + perPlayer.getMessage());
                    }
                }
                index[0] = end;
                done = end >= ordered.size();
            } catch (RuntimeException batchEx) {
                logger.warning("FFA batch tick failed; finishing batch: " + batchEx.getMessage());
                index[0] = ordered.size();
                done = true;
            } finally {
                if (done && !completed[0]) {
                    completed[0] = true;
                    if (holder[0] != null) {
                        holder[0].cancel();
                    }
                    if (onComplete != null) {
                        try {
                            onComplete.run();
                        } catch (RuntimeException completeEx) {
                            logger.warning("FFA onComplete failed: " + completeEx.getMessage());
                        }
                    }
                }
            }
        }, 0L, 1L);
        return holder[0];
    }
}
