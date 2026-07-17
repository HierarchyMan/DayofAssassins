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
    void ellipseRadiiCappedByMaxDiameter() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 200, 100, 200);
        double[] r = RingSpawnMath.ellipseRadii(cuboid, 100, 100, 2, 0.75);
        // max diameter 150 → max radius 75
        assertTrue(r[0] <= 75.0 + 1e-9);
        assertTrue(r[1] <= 75.0 + 1e-9);
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
            assertTrue(cuboid.containsHorizontal(p.x(), p.z())
                    || (p.x() >= cuboid.minX() && p.x() <= cuboid.maxX()));
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
    }

    @Test
    void diameterCapNotExceededByOuterRingRadii() {
        CuboidBounds cuboid = new CuboidBounds(0, 0, 0, 80, 100, 40);
        double maxD = RingSpawnMath.maxDiameter(cuboid, 0.75);
        double[] r = RingSpawnMath.ellipseRadii(cuboid, 40, 20, 2, 0.75);
        assertTrue(2 * r[0] <= maxD + 1e-6 || r[0] <= maxD / 2 + 1e-6);
        assertTrue(2 * Math.max(r[0], r[1]) <= maxD + 1.0);
    }
}
