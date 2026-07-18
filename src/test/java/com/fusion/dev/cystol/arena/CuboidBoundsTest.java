package com.fusion.dev.cystol.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuboidBoundsTest {

    @Test
    void flatCuboidDetectedAndExpanded() {
        CuboidBounds flat = new CuboidBounds(0, 64, 0, 40, 64, 40);
        assertTrue(flat.isVerticallyFlat(0.5));
        assertEquals(0.0, flat.sizeY(), 1e-9);

        CuboidBounds tall = flat.withMinimumHeight(24);
        assertFalse(tall.isVerticallyFlat(0.5));
        assertEquals(24.0, tall.sizeY(), 1e-9);
        assertEquals(0.0, tall.minX(), 1e-9);
        assertEquals(40.0, tall.maxX(), 1e-9);
        // mid at 64 → 52 .. 76
        assertEquals(52.0, tall.minY(), 1e-9);
        assertEquals(76.0, tall.maxY(), 1e-9);
    }

    @Test
    void alreadyTallUnchanged() {
        CuboidBounds box = new CuboidBounds(0, 60, 0, 10, 90, 10);
        assertSame(box, box.withMinimumHeight(24));
        assertEquals(30.0, box.sizeY(), 1e-9);
    }

    @Test
    void containsInclusiveForSpawnZoneGate() {
        CuboidBounds spawn = new CuboidBounds(100, 64, 200, 120, 80, 220);
        assertTrue(spawn.contains(100, 64, 200));
        assertTrue(spawn.contains(120, 80, 220));
        assertTrue(spawn.contains(110, 72, 210));
        assertFalse(spawn.contains(99.9, 72, 210));
        assertFalse(spawn.contains(110, 63.9, 210));
        assertFalse(spawn.contains(110, 72, 220.1));
        // wrong axis outside
        assertFalse(spawn.contains(110, 72, 199));
        assertTrue(spawn.containsHorizontal(110, 210));
        assertFalse(spawn.containsHorizontal(50, 210));
    }
}
