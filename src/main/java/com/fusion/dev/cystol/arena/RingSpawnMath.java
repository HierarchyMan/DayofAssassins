package com.fusion.dev.cystol.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Pure spawn placement: diameter ≤ fraction of longest cuboid side;
 * multi-ring / random when points would be closer than 1 block.
 */
public final class RingSpawnMath {

    public record SpawnPoint(double x, double y, double z, float yaw) {
    }

    private RingSpawnMath() {
    }

    /**
     * Max diameter for the outermost ring (not radius).
     */
    public static double maxDiameter(CuboidBounds cuboid, double maxDiameterFraction) {
        Objects.requireNonNull(cuboid, "cuboid");
        double frac = maxDiameterFraction <= 0 ? 0.75 : Math.min(1.0, maxDiameterFraction);
        return cuboid.longestHorizontalSide() * frac;
    }

    /**
     * Max half-axis from diameter cap only (ignores cuboid edges / center).
     * Prefer {@link #ellipseRadii} for actual placement bounds.
     */
    public static double maxRadius(CuboidBounds cuboid, double maxDiameterFraction) {
        return maxDiameter(cuboid, maxDiameterFraction) / 2.0;
    }

    /**
     * Ellipse half-radii from center inside cuboid with margin, capped by max diameter fraction.
     * Half-axes are never forced larger than free space (avoids spilling outside the cuboid
     * when the center is near an edge).
     */
    public static double[] ellipseRadii(CuboidBounds cuboid, double cx, double cz, double margin, double maxDiameterFraction) {
        double m = Math.max(0, margin);
        // Free space from center to each edge, then subtract margin; never negative.
        double halfX = Math.max(0, Math.min(cx - cuboid.minX(), cuboid.maxX() - cx) - m);
        double halfZ = Math.max(0, Math.min(cz - cuboid.minZ(), cuboid.maxZ() - cz) - m);
        double maxR = maxDiameter(cuboid, maxDiameterFraction) / 2.0;
        halfX = Math.min(halfX, maxR);
        halfZ = Math.min(halfZ, maxR);
        return new double[]{halfX, halfZ};
    }

    /**
     * Ring half-radius so adjacent players on a regular N-gon have chord ≈ {@code minSpacing}.
     * For N=2 that is spacing/2 (pair distance = spacing). Grows with N; caller clamps to arena max.
     */
    public static double ringRadiusForSpacing(int playerCount, double minSpacing) {
        if (playerCount <= 1) {
            return 0.0;
        }
        double spacing = Math.max(1.0, minSpacing);
        // chord = 2 * r * sin(π/N)  →  r = chord / (2 sin(π/N))
        double sin = Math.sin(Math.PI / playerCount);
        if (sin < 1e-9) {
            return spacing;
        }
        return spacing / (2.0 * sin);
    }

    /**
     * Scale max ellipse half-axes down so few players stand close enough to see each other,
     * while many players still use the full arena cap.
     *
     * @return {{@code rx}, {@code rz}} after player-count scaling
     */
    public static double[] scaleRadiiForPlayerCount(double maxRx, double maxRz, int playerCount, double minSpacing) {
        if (playerCount <= 1) {
            return new double[]{0.0, 0.0};
        }
        double maxHalf = Math.min(maxRx, maxRz);
        if (maxHalf <= 1e-9) {
            // Degenerate free space — keep axes as-is (likely near-edge center).
            return new double[]{maxRx, maxRz};
        }
        double target = ringRadiusForSpacing(playerCount, minSpacing);
        // Never exceed arena max; never force larger than free space.
        double scale = Math.min(1.0, target / maxHalf);
        // Also expand toward target when maxHalf is the limiting axis on one side only:
        // scale both axes uniformly so ellipse shape is preserved.
        return new double[]{maxRx * scale, maxRz * scale};
    }

    /**
     * Generate N horizontal points. If adjacent spacing on single ring &lt; 1 block,
     * use concentric rings; if still congested, use random viable samples.
     *
     * <p>Ring size scales with player count: few players → tight ring (≈ {@code minSpacing}
     * between neighbors); many players → up to the diameter-fraction arena cap.
     *
     * @param standableXZ predicate on (x,z) — pure; y filled by preferredY or 0 for math-only tests
     * @param minSpacing  target block distance between adjacent ring mates (default ~8)
     */
    public static List<SpawnPoint> computeSpawns(
            CuboidBounds cuboid,
            double cx,
            double cy,
            double cz,
            int playerCount,
            double margin,
            double maxDiameterFraction,
            double minSpacing,
            Predicate<double[]> standableXZ,
            Random random
    ) {
        Objects.requireNonNull(cuboid, "cuboid");
        if (playerCount <= 0) {
            return List.of();
        }
        Random rng = random == null ? new Random(0) : random;
        Predicate<double[]> ok = standableXZ == null
                ? xz -> cuboid.containsHorizontal(xz[0], xz[1])
                : standableXZ;

        // Keep center inside cuboid so degenerate radii still land in-bounds.
        double clampedCx = clamp(cx, cuboid.minX(), cuboid.maxX());
        double clampedCz = clamp(cz, cuboid.minZ(), cuboid.maxZ());

        double[] maxRadii = ellipseRadii(cuboid, clampedCx, clampedCz, margin, maxDiameterFraction);
        double[] radii = scaleRadiiForPlayerCount(maxRadii[0], maxRadii[1], playerCount, minSpacing);
        double rx = radii[0];
        double rz = radii[1];

        List<SpawnPoint> single = ringPoints(clampedCx, cy, clampedCz, rx, rz, playerCount);
        if (playerCount == 1 || minPairDistance(single) >= 1.0 - 1e-6) {
            return filterStandable(single, ok, cuboid, clampedCx, cy, clampedCz, rng);
        }

        // Multi-ring: split players across rings with scaled radii
        int rings = Math.max(2, (int) Math.ceil(Math.sqrt(playerCount)));
        List<SpawnPoint> multi = new ArrayList<>();
        int remaining = playerCount;
        for (int r = 0; r < rings && remaining > 0; r++) {
            double scale = (r + 1) / (double) rings;
            int onRing = r == rings - 1 ? remaining : Math.max(1, remaining / (rings - r));
            multi.addAll(ringPoints(clampedCx, cy, clampedCz, rx * scale, rz * scale, onRing));
            remaining -= onRing;
        }
        while (multi.size() > playerCount) {
            multi.remove(multi.size() - 1);
        }
        while (multi.size() < playerCount) {
            multi.add(new SpawnPoint(clampedCx, cy, clampedCz, 0f));
        }

        if (minPairDistance(multi) >= 1.0 - 1e-6) {
            return filterStandable(multi, ok, cuboid, clampedCx, cy, clampedCz, rng);
        }

        // Random viable locations inside ellipse (uniform disk mapped to ellipse)
        List<SpawnPoint> randomPoints = new ArrayList<>();
        int attempts = playerCount * 40;
        for (int i = 0; i < attempts && randomPoints.size() < playerCount; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(rng.nextDouble());
            double x = clampedCx + Math.cos(angle) * rx * dist;
            double z = clampedCz + Math.sin(angle) * rz * dist;
            if (!cuboid.containsHorizontal(x, z) || !ok.test(new double[]{x, z})) {
                continue;
            }
            if (tooClose(randomPoints, x, z, 1.0)) {
                continue;
            }
            float yaw = yawToward(x, z, clampedCx, clampedCz);
            randomPoints.add(new SpawnPoint(x, cy, z, yaw));
        }
        // Congested / tiny arena: fill near center with light jitter (may stack — last resort)
        while (randomPoints.size() < playerCount) {
            double jx = rx > 0 ? (rng.nextDouble() - 0.5) * Math.min(2.0, rx) : 0;
            double jz = rz > 0 ? (rng.nextDouble() - 0.5) * Math.min(2.0, rz) : 0;
            double x = clamp(clampedCx + jx, cuboid.minX(), cuboid.maxX());
            double z = clamp(clampedCz + jz, cuboid.minZ(), cuboid.maxZ());
            float yaw = yawToward(x, z, clampedCx, clampedCz);
            randomPoints.add(new SpawnPoint(x, cy, z, yaw));
        }
        return randomPoints;
    }

    /**
     * Backward-compatible overload: default min spacing of 8 blocks between neighbors.
     */
    public static List<SpawnPoint> computeSpawns(
            CuboidBounds cuboid,
            double cx,
            double cy,
            double cz,
            int playerCount,
            double margin,
            double maxDiameterFraction,
            Predicate<double[]> standableXZ,
            Random random
    ) {
        return computeSpawns(
                cuboid, cx, cy, cz, playerCount, margin, maxDiameterFraction,
                8.0, standableXZ, random
        );
    }

    private static List<SpawnPoint> ringPoints(double cx, double cy, double cz, double rx, double rz, int n) {
        List<SpawnPoint> list = new ArrayList<>(n);
        if (n <= 0) {
            return list;
        }
        if (n == 1 || (rx <= 1e-9 && rz <= 1e-9)) {
            // Degenerate ring or single player: stack at center facing default south.
            for (int i = 0; i < n; i++) {
                list.add(new SpawnPoint(cx, cy, cz, 0f));
            }
            return list;
        }
        for (int i = 0; i < n; i++) {
            double theta = (2 * Math.PI * i) / n;
            double x = cx + rx * Math.cos(theta);
            double z = cz + rz * Math.sin(theta);
            float yaw = yawToward(x, z, cx, cz);
            list.add(new SpawnPoint(x, cy, z, yaw));
        }
        return list;
    }

    private static List<SpawnPoint> filterStandable(
            List<SpawnPoint> points,
            Predicate<double[]> ok,
            CuboidBounds cuboid,
            double cx,
            double cy,
            double cz,
            Random rng
    ) {
        List<SpawnPoint> out = new ArrayList<>(points.size());
        for (SpawnPoint p : points) {
            if (ok.test(new double[]{p.x(), p.z()}) && cuboid.containsHorizontal(p.x(), p.z())) {
                out.add(p);
                continue;
            }
            // Nudge toward center, then clamp — keeps fallback inside cuboid.
            double x = clamp(p.x(), cuboid.minX(), cuboid.maxX());
            double z = clamp(p.z(), cuboid.minZ(), cuboid.maxZ());
            if (!ok.test(new double[]{x, z})) {
                x = cx + (rng.nextDouble() - 0.5) * 0.25;
                z = cz + (rng.nextDouble() - 0.5) * 0.25;
                x = clamp(x, cuboid.minX(), cuboid.maxX());
                z = clamp(z, cuboid.minZ(), cuboid.maxZ());
            }
            out.add(new SpawnPoint(x, cy, z, yawToward(x, z, cx, cz)));
        }
        return out;
    }

    private static double minPairDistance(List<SpawnPoint> pts) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                double dx = pts.get(i).x() - pts.get(j).x();
                double dz = pts.get(i).z() - pts.get(j).z();
                min = Math.min(min, Math.hypot(dx, dz));
            }
        }
        return min == Double.MAX_VALUE ? Double.MAX_VALUE : min;
    }

    private static boolean tooClose(List<SpawnPoint> pts, double x, double z, double min) {
        for (SpawnPoint p : pts) {
            if (Math.hypot(p.x() - x, p.z() - z) < min) {
                return true;
            }
        }
        return false;
    }

    /**
     * Minecraft yaw looking from (x,z) toward (cx,cz): 0 = +Z (south), 90 = -X (west).
     */
    private static float yawToward(double x, double z, double cx, double cz) {
        double dx = cx - x;
        double dz = cz - z;
        if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) {
            return 0f;
        }
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private static double clamp(double v, double lo, double hi) {
        if (lo > hi) {
            return (lo + hi) / 2.0;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
