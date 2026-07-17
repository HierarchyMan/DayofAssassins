package com.fusion.dev.cystol.teleport;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportLockServiceTest {

    @Test
    void primaryCommandLabelStripsSlashNamespaceAndArgs() {
        assertEquals("spawn", TeleportLockService.primaryCommandLabel("/spawn"));
        assertEquals("spawn", TeleportLockService.primaryCommandLabel("/essentials:spawn"));
        assertEquals("home", TeleportLockService.primaryCommandLabel("/home base"));
        assertEquals("back", TeleportLockService.primaryCommandLabel("BACK"));
        assertEquals("", TeleportLockService.primaryCommandLabel("   "));
    }

    @Test
    void blockedLabelMatch() {
        Set<String> blocked = Set.of("spawn", "home", "back");
        assertTrue(TeleportLockService.isBlockedLabel("spawn", blocked));
        assertTrue(TeleportLockService.isBlockedLabel("HOME", blocked));
        assertFalse(TeleportLockService.isBlockedLabel("msg", blocked));
        assertFalse(TeleportLockService.isBlockedLabel("", blocked));
    }
}
