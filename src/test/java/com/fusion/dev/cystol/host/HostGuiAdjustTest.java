package com.fusion.dev.cystol.host;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Click step math for host GUI number pads — not menu wiring. */
class HostGuiAdjustTest {

    @Test
    void adjustIntLeftRightAndShift() {
        assertEquals(4, HostGui.adjustInt(3, ClickType.LEFT, 1, 5, 0, 15));
        assertEquals(2, HostGui.adjustInt(3, ClickType.RIGHT, 1, 5, 0, 15));
        assertEquals(8, HostGui.adjustInt(3, ClickType.SHIFT_LEFT, 1, 5, 0, 15));
        assertEquals(0, HostGui.adjustInt(3, ClickType.SHIFT_RIGHT, 1, 5, 0, 15));
        assertEquals(15, HostGui.adjustInt(14, ClickType.LEFT, 1, 5, 0, 15));
    }

    @Test
    void adjustLongClamps() {
        assertEquals(3660, HostGui.adjustLong(3600, ClickType.LEFT, 60, 600, 0, 99999));
        assertEquals(0, HostGui.adjustLong(30, ClickType.RIGHT, 60, 600, 0, 99999));
    }
}
