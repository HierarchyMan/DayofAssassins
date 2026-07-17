package com.fusion.dev.cystol.arena;

import com.fusion.dev.cystol.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public final class FfaSpawnService {

    private final PluginConfig config;
    private final Logger logger;

    public FfaSpawnService(PluginConfig config, Logger logger) {
        this.config = config;
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
            if (p.hasMetadata("vanished") && !p.getMetadata("vanished").isEmpty()
                    && p.getMetadata("vanished").getFirst().asBoolean()) {
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

        List<Location> locations = new ArrayList<>(points.size());
        for (RingSpawnMath.SpawnPoint sp : points) {
            int y = findStandableY(world, sp.x(), sp.z(), cy, cuboid, yRange, air);
            Location loc = new Location(world, sp.x(), y, sp.z(), sp.yaw(), 0f);
            locations.add(loc);
        }
        return locations;
    }

    /**
     * Prefer Y closest to centerY within search range; require solid below and air above.
     */
    public int findStandableY(World world, double x, double z, double preferredY,
                              CuboidBounds cuboid, int ySearchRange, int minAirAbove) {
        int base = (int) Math.floor(preferredY);
        int minY = Math.max(cuboid.minY() == (int) cuboid.minY() ? (int) cuboid.minY() : (int) Math.floor(cuboid.minY()),
                world.getMinHeight());
        int maxY = Math.min((int) Math.floor(cuboid.maxY()), world.getMaxHeight() - 3);

        int bestY = base;
        int bestDist = Integer.MAX_VALUE;
        for (int d = 0; d <= ySearchRange; d++) {
            for (int sign : d == 0 ? new int[]{0} : new int[]{1, -1}) {
                int y = base + sign * d;
                if (y < minY || y > maxY) {
                    continue;
                }
                if (isSafe(world, x, y, z, minAirAbove)) {
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
        return Math.max(minY, Math.min(maxY, base));
    }

    private boolean isSafe(World world, double x, int y, double z, int minAirAbove) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Block floor = world.getBlockAt(bx, y - 1, bz);
        if (!floor.getType().isSolid() || floor.isLiquid()) {
            return false;
        }
        for (int i = 0; i < minAirAbove; i++) {
            Block air = world.getBlockAt(bx, y + i, bz);
            if (air.getType() != Material.AIR && !air.isPassable()) {
                return false;
            }
        }
        return true;
    }

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
}
