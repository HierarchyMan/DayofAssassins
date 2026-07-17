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

    public static double maxRadius(CuboidBounds cuboid, double maxDiameterFraction, double margin) {
        double halfByDiameter = maxDiameter(cuboid, maxDiameterFraction) / 2.0;
        double halfX = Math.max(0, Math.min(cuboid.maxX() - margin, Math.max(0, /* placeholder */ 0)));
        // usable half extents from center — caller passes clamped center
        return halfByDiameter;
    }

    /**
     * Ellipse half-radii from center inside cuboid with margin, capped by max diameter fraction.
     */
    public static double[] ellipseRadii(CuboidBounds cuboid, double cx, double cz, double margin, double maxDiameterFraction) {
        double halfX = Math.min(cx - cuboid.minX(), cuboid.maxX() - cx) - margin;
        double halfZ = Math.min(cz - cuboid.minZ(), cuboid.maxZ() - cz) - margin;
        halfX = Math.max(0.5, halfX);
        halfZ = Math.max(0.5, halfZ);
        double maxR = maxDiameter(cuboid, maxDiameterFraction) / 2.0;
        halfX = Math.min(halfX, maxR);
        halfZ = Math.min(halfZ, maxR);
        return new double[]{halfX, halfZ};
    }

    /**
     * Generate N horizontal points. If adjacent spacing on single ring &lt; 1 block,
     * use concentric rings; if still congested, use random viable samples.
     *
     * @param standable predicate on (x,z) — pure; y filled by preferredY or 0 for math-only tests
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
        Objects.requireNonNull(cuboid, "cuboid");
        if (playerCount <= 0) {
            return List.of();
        }
        Random rng = random == null ? new Random(0) : random;
        Predicate<double[]> ok = standableXZ == null ? xz -> cuboid.containsHorizontal(xz[0], xz[1]) : standableXZ;

        double[] radii = ellipseRadii(cuboid, cx, cz, margin, maxDiameterFraction);
        double rx = radii[0];
        double rz = radii[1];

        List<SpawnPoint> single = ringPoints(cx, cy, cz, rx, rz, playerCount, 1);
        if (playerCount == 1 || minPairDistance(single) >= 1.0 - 1e-6) {
            return filterStandable(single, ok, cuboid, cy, rng);
        }

        // Multi-ring: split players across rings with scaled radii
        int rings = Math.max(2, (int) Math.ceil(Math.sqrt(playerCount)));
        List<SpawnPoint> multi = new ArrayList<>();
        int remaining = playerCount;
        for (int r = 0; r < rings && remaining > 0; r++) {
            double scale = (r + 1) / (double) rings;
            int onRing = r == rings - 1 ? remaining : Math.max(1, remaining / (rings - r));
            multi.addAll(ringPoints(cx, cy, cz, rx * scale, rz * scale, onRing, 1));
            remaining -= onRing;
        }
        while (multi.size() > playerCount) {
            multi.remove(multi.size() - 1);
        }
        while (multi.size() < playerCount) {
            multi.add(new SpawnPoint(cx, cy, cz, 0f));
        }

        if (minPairDistance(multi) >= 1.0 - 1e-6) {
            return filterStandable(multi, ok, cuboid, cy, rng);
        }

        // Random viable locations inside ellipse bounding box
        List<SpawnPoint> randomPoints = new ArrayList<>();
        int attempts = playerCount * 40;
        for (int i = 0; i < attempts && randomPoints.size() < playerCount; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(rng.nextDouble());
            double x = cx + Math.cos(angle) * rx * dist;
            double z = cz + Math.sin(angle) * rz * dist;
            if (!ok.test(new double[]{x, z})) {
                continue;
            }
            if (tooClose(randomPoints, x, z, 1.0)) {
                continue;
            }
            float yaw = yawToward(x, z, cx, cz);
            randomPoints.add(new SpawnPoint(x, cy, z, yaw));
        }
        while (randomPoints.size() < playerCount) {
            // fill remaining at center jitter
            double x = cx + (rng.nextDouble() - 0.5) * Math.min(2, rx);
            double z = cz + (rng.nextDouble() - 0.5) * Math.min(2, rz);
            float yaw = yawToward(x, z, cx, cz);
            randomPoints.add(new SpawnPoint(x, cy, z, yaw));
        }
        return randomPoints;
    }

    private static List<SpawnPoint> ringPoints(double cx, double cy, double cz, double rx, double rz, int n, int _unused) {
        List<SpawnPoint> list = new ArrayList<>(n);
        if (n <= 0) {
            return list;
        }
        if (n == 1) {
            list.add(new SpawnPoint(cx, cy, cz, 0f));
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
            double cy,
            Random rng
    ) {
        List<SpawnPoint> out = new ArrayList<>(points.size());
        for (SpawnPoint p : points) {
            if (ok.test(new double[]{p.x(), p.z()})) {
                out.add(p);
            } else {
                out.add(new SpawnPoint(
                        clamp(p.x(), cuboid.minX(), cuboid.maxX()),
                        cy,
                        clamp(p.z(), cuboid.minZ(), cuboid.maxZ()),
                        p.yaw()
                ));
            }
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

    private static float yawToward(double x, double z, double cx, double cz) {
        double dx = cx - x;
        double dz = cz - z;
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
