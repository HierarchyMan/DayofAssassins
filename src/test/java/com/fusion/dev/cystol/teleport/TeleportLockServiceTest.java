package com.fusion.dev.cystol.teleport;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

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

    @Test
    void temporaryAllowExpiresByTime() throws InterruptedException {
        // allowTemporarily / hasTemporaryAllow only touch the expiry map (deps unused).
        TeleportLockService service = new TeleportLockService(null, null);
        UUID id = UUID.randomUUID();
        assertFalse(service.hasTemporaryAllow(id));
        service.allowTemporarily(id, 5_000L);
        assertTrue(service.hasTemporaryAllow(id));
        service.allowTemporarily(id, 1L);
        // merge keeps max — still within 5s window
        assertTrue(service.hasTemporaryAllow(id));
        UUID shortLived = UUID.randomUUID();
        service.allowTemporarily(shortLived, 1L);
        Thread.sleep(5L);
        assertFalse(service.hasTemporaryAllow(shortLived));
    }
}
