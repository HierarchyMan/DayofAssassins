package com.fusion.dev.cystol.arena;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.util.VanishService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
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
        int air = config.minAirAbove();
        int yRange = config.ySearchRange();

        Random rng = ThreadLocalRandom.current();
        List<RingSpawnMath.SpawnPoint> points = RingSpawnMath.computeSpawns(
                cuboid, cx, cy, cz, count, margin, frac,
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
            int y = findStandableY(world, sp.x(), sp.z(), cy, cuboid, yRange, air);
            Location loc = new Location(world, sp.x(), y, sp.z(), sp.yaw(), 0f);
            locations.add(loc);
        }
        return locations;
    }

    /**
     * Prefer Y closest to centerY within search range; require solid floor and clear air column.
     * Feet Y is the block coordinate the player stands in (floor at y-1).
     */
    public int findStandableY(World world, double x, double z, double preferredY,
                              CuboidBounds cuboid, int ySearchRange, int minAirAbove) {
        int base = (int) Math.floor(preferredY);
        int air = Math.max(1, minAirAbove);
        int minY = Math.max((int) Math.floor(cuboid.minY()), world.getMinHeight() + 1);
        // Leave room for the air column and keep head inside cuboid / world.
        int maxByWorld = world.getMaxHeight() - air;
        int maxByCuboid = (int) Math.floor(cuboid.maxY()) - (air - 1);
        int maxY = Math.min(maxByCuboid, maxByWorld);
        if (maxY < minY) {
            // Degenerate cuboid height — fall back to preferred, clamped to world.
            return Math.max(world.getMinHeight() + 1, Math.min(maxByWorld, base));
        }

        int bestY = base;
        int bestDist = Integer.MAX_VALUE;
        for (int d = 0; d <= ySearchRange; d++) {
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
                            return bestY;
                        }
                    }
                }
            }
        }
        if (bestDist != Integer.MAX_VALUE) {
            return bestY;
        }
        int fallback = Math.max(minY, Math.min(maxY, base));
        logger.warning(String.format(
                "No standable Y near (%.1f, %.1f) preferredY=%d; using fallback y=%d",
                x, z, base, fallback
        ));
        return fallback;
    }

    /**
     * Solid non-liquid floor at y-1; feet + head column must be passable and non-liquid
     * for {@code minAirAbove} blocks (design: ≥2 air above feet by default).
     */
    private boolean isSafe(World world, double x, int y, double z, int minAirAbove) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Block floor = world.getBlockAt(bx, y - 1, bz);
        if (!floor.getType().isSolid() || floor.isLiquid()) {
            return false;
        }
        for (int i = 0; i < minAirAbove; i++) {
            Block space = world.getBlockAt(bx, y + i, bz);
            if (space.isLiquid()) {
                return false;
            }
            // Reject solid / non-passable (fences, glass, etc.). Allow air, cave air, plants.
            if (!space.isPassable()) {
                return false;
            }
        }
        return true;
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
        final int[] index = {0};
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int start = index[0];
            int end = Math.min(start + batch, ordered.size());
            for (int i = start; i < end; i++) {
                Player p = ordered.get(i);
                if (p == null || !p.isOnline()) {
                    continue;
                }
                Location loc = i < locs.size() ? locs.get(i) : locs.isEmpty() ? null : locs.get(0);
                if (loc == null) {
                    continue;
                }
                p.teleport(loc);
                if (afterEach != null) {
                    afterEach.accept(p);
                }
            }
            index[0] = end;
            if (end >= ordered.size()) {
                if (holder[0] != null) {
                    holder[0].cancel();
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, 0L, 1L);
        return holder[0];
    }
}
