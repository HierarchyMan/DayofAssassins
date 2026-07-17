package com.fusion.dev.cystol.arena;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingSpawnMathTest {

    @Test
    void maxDiameterIsFractionOfLongestSide() {
        CuboidBounds cuboid = new CuboidBounds(0, 64, 0, 100, 80, 40);
        // longest horizontal is 100
        assertEquals(75.0, RingSpawnMath.maxDiameter(cuboid, 0.75), 1e-9);
    }

    @Test
    void maxRadiusIsHalfOfMaxDiameter() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 100, 80, 40);
        assertEquals(37.5, RingSpawnMath.maxRadius(cuboid, 0.75), 1e-9);
    }

    @Test
    void ellipseRadiiCappedByMaxDiameter() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 200, 100, 200);
        double[] r = RingSpawnMath.ellipseRadii(cuboid, 100, 100, 2, 0.75);
        // max diameter 150 → max radius 75
        assertTrue(r[0] <= 75.0 + 1e-9);
        assertTrue(r[1] <= 75.0 + 1e-9);
    }

    @Test
    void ellipseRadiiRespectsMarginFromEdges() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 100, 80, 100);
        double margin = 2;
        double[] r = RingSpawnMath.ellipseRadii(cuboid, 50, 50, margin, 1.0);
        // free half = 50 - 2 = 48, maxR = 50 → both 48
        assertEquals(48.0, r[0], 1e-9);
        assertEquals(48.0, r[1], 1e-9);
    }

    @Test
    void ellipseRadiiNearEdgeDoesNotInventSpace() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 100, 80, 100);
        // Center 1 block from min edge, margin 2 → free space after margin is 0, not 0.5
        double[] r = RingSpawnMath.ellipseRadii(cuboid, 1, 50, 2, 0.75);
        assertEquals(0.0, r[0], 1e-9);
        assertTrue(r[1] > 0);
    }

    @Test
    void computeSpawnsReturnsRequestedCount() {
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 100, 80, 100);
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, 50, 65, 50, 8, 2, 0.75,
                xz -> cuboid.containsHorizontal(xz[0], xz[1]),
                new Random(42)
        );
        assertEquals(8, pts.size());
        for (RingSpawnMath.SpawnPoint p : pts) {
            assertTrue(cuboid.containsHorizontal(p.x(), p.z()),
                    "spawn outside cuboid: " + p.x() + "," + p.z());
        }
    }

    @Test
    void congestedManyPlayersStillReturnsCount() {
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 10, 80, 10);
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, 5, 65, 5, 40, 1, 0.75,
                xz -> true,
                new Random(7)
        );
        assertEquals(40, pts.size());
        for (RingSpawnMath.SpawnPoint p : pts) {
            assertTrue(cuboid.containsHorizontal(p.x(), p.z()));
        }
    }

    @Test
    void diameterCapNotExceededByOuterRingRadii() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 80, 100, 40);
        double maxD = RingSpawnMath.maxDiameter(cuboid, 0.75);
        double[] r = RingSpawnMath.ellipseRadii(cuboid, 40, 20, 2, 0.75);
        assertTrue(2 * Math.max(r[0], r[1]) <= maxD + 1e-6);
    }

    @Test
    void singleRingSpawnsStayInsideEllipseAndCuboid() {
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 100, 80, 100);
        double cx = 50.5, cz = 50.5, margin = 2, frac = 0.75;
        double[] r = RingSpawnMath.ellipseRadii(cuboid, cx, cz, margin, frac);
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, cx, 65, cz, 12, margin, frac,
                xz -> cuboid.containsHorizontal(xz[0], xz[1]),
                new Random(1)
        );
        assertEquals(12, pts.size());
        double maxD = RingSpawnMath.maxDiameter(cuboid, frac);
        for (RingSpawnMath.SpawnPoint p : pts) {
            assertTrue(cuboid.containsHorizontal(p.x(), p.z()));
            // Point must lie inside or on the placement ellipse (with tiny FP slack)
            double nx = (p.x() - cx) / Math.max(r[0], 1e-9);
            double nz = (p.z() - cz) / Math.max(r[1], 1e-9);
            assertTrue(nx * nx + nz * nz <= 1.0 + 1e-6,
                    "outside ellipse: " + p);
            double distFromCenter = Math.hypot(p.x() - cx, p.z() - cz);
            assertTrue(distFromCenter <= maxD / 2 + 1.0);
        }
    }

    @Test
    void spaciousArenaKeepsMinPairDistanceAtLeastOne() {
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 200, 80, 200);
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, 100, 65, 100, 16, 2, 0.75,
                xz -> cuboid.containsHorizontal(xz[0], xz[1]),
                new Random(99)
        );
        assertEquals(16, pts.size());
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                double d = Math.hypot(pts.get(i).x() - pts.get(j).x(), pts.get(i).z() - pts.get(j).z());
                assertTrue(d >= 1.0 - 1e-6, "pair too close: " + d);
            }
        }
    }

    @Test
    void centerOutsideCuboidStillSpawnsInside() {
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 50, 80, 50);
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, -20, 65, -20, 6, 2, 0.75,
                xz -> cuboid.containsHorizontal(xz[0], xz[1]),
                new Random(3)
        );
        assertEquals(6, pts.size());
        for (RingSpawnMath.SpawnPoint p : pts) {
            assertTrue(cuboid.containsHorizontal(p.x(), p.z()),
                    "outside after center clamp: " + p.x() + "," + p.z());
        }
    }

    @Test
    void yawFacesTowardCenter() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 100, 80, 100);
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, 50, 65, 50, 4, 2, 0.75,
                xz -> true,
                new Random(0)
        );
        // 4 players at cardinal-ish ellipse points should face inward (toward center)
        for (RingSpawnMath.SpawnPoint p : pts) {
            if (Math.hypot(p.x() - 50, p.z() - 50) < 0.1) {
                continue;
            }
            // Direction from spawn to center should match yaw (MC: 0=+Z, 90=-X)
            double dx = 50 - p.x();
            double dz = 50 - p.z();
            float expected = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float diff = Math.abs(p.yaw() - expected);
            while (diff > 180) {
                diff = Math.abs(diff - 360);
            }
            assertTrue(diff < 0.5f, "yaw " + p.yaw() + " vs " + expected);
        }
    }
}
