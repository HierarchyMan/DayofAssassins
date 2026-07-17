package com.fusion.dev.cystol.arena;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spawn geometry only — edges, caps, congestion, in-bounds. Not formula re-derives.
 */
class RingSpawnMathTest {

    @Test
    void ellipseRadiiRespectMarginAndDiameterCap() {
        CuboidBounds wide = new CuboidBounds(0, 0, 0, 200, 100, 200);
        double[] big = RingSpawnMath.ellipseRadii(wide, 100, 100, 2, 0.75);
        // longest side 200 → max diameter 150 → half 75
        assertTrue(big[0] <= 75.0 + 1e-9);
        assertTrue(big[1] <= 75.0 + 1e-9);

        CuboidBounds box = new CuboidBounds(0, 0, 0, 100, 80, 100);
        double[] mid = RingSpawnMath.ellipseRadii(box, 50, 50, 2, 1.0);
        assertEquals(48.0, mid[0], 1e-9);
        assertEquals(48.0, mid[1], 1e-9);

        // Near edge + margin must not invent free space
        double[] edge = RingSpawnMath.ellipseRadii(box, 1, 50, 2, 0.75);
        assertEquals(0.0, edge[0], 1e-9);
        assertTrue(edge[1] > 0);
    }

    @Test
    void computeSpawnsStayInCuboidAndHonorCount() {
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 100, 80, 100);
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, 50, 65, 50, 12, 2, 0.75,
                xz -> cuboid.containsHorizontal(xz[0], xz[1]),
                new Random(1)
        );
        assertEquals(12, pts.size());
        double[] r = RingSpawnMath.ellipseRadii(cuboid, 50, 50, 2, 0.75);
        for (RingSpawnMath.SpawnPoint p : pts) {
            assertTrue(cuboid.containsHorizontal(p.x(), p.z()), p.x() + "," + p.z());
            double nx = (p.x() - 50) / Math.max(r[0], 1e-9);
            double nz = (p.z() - 50) / Math.max(r[1], 1e-9);
            assertTrue(nx * nx + nz * nz <= 1.0 + 1e-6, "outside ellipse: " + p);
        }
    }

    @Test
    void congestedArenaStillReturnsCountInBounds() {
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
            assertTrue(cuboid.containsHorizontal(p.x(), p.z()), p.x() + "," + p.z());
        }
    }

    @Test
    void twoPlayersUseTightRingNotFullArenaDiameter() {
        // Huge arena: old logic put 2 players ~75 blocks apart (barely visible).
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 200, 80, 200);
        double spacing = 8.0;
        List<RingSpawnMath.SpawnPoint> pts = RingSpawnMath.computeSpawns(
                cuboid, 100, 65, 100, 2, 2, 0.75, spacing,
                xz -> cuboid.containsHorizontal(xz[0], xz[1]),
                new Random(1)
        );
        assertEquals(2, pts.size());
        double dist = Math.hypot(pts.get(0).x() - pts.get(1).x(), pts.get(0).z() - pts.get(1).z());
        // Chord ≈ spacing (allow small numeric slack)
        assertEquals(spacing, dist, 0.5);
        // Far smaller than max diameter half*2 (~150)
        assertTrue(dist < 20.0, "pair too far apart: " + dist);
    }

    @Test
    void manyPlayersGrowTowardDiameterCap() {
        CuboidBounds cuboid = new CuboidBounds(0, 60, 0, 200, 80, 200);
        List<RingSpawnMath.SpawnPoint> few = RingSpawnMath.computeSpawns(
                cuboid, 100, 65, 100, 4, 2, 0.75, 8.0, xz -> true, new Random(2)
        );
        List<RingSpawnMath.SpawnPoint> many = RingSpawnMath.computeSpawns(
                cuboid, 100, 65, 100, 40, 2, 0.75, 8.0, xz -> true, new Random(2)
        );
        double fewR = Math.hypot(few.getFirst().x() - 100, few.getFirst().z() - 100);
        double manyR = Math.hypot(many.getFirst().x() - 100, many.getFirst().z() - 100);
        assertTrue(manyR > fewR + 5.0, "manyR=" + manyR + " fewR=" + fewR);
    }

    @Test
    void ringRadiusForSpacingMatchesChordFormula() {
        // N=2 → r = spacing/2
        assertEquals(4.0, RingSpawnMath.ringRadiusForSpacing(2, 8.0), 1e-9);
        // N=4 → r = spacing / (2 * sin(π/4)) = 8 / (2 * √2/2) = 8/√2 = 4√2
        assertEquals(8.0 / Math.sqrt(2.0), RingSpawnMath.ringRadiusForSpacing(4, 8.0), 1e-9);
    }
}
