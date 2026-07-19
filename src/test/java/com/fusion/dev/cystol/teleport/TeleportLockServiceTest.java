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
        assertEquals("mvtp", TeleportLockService.primaryCommandLabel("/mv:mvtp world"));
        assertEquals("", TeleportLockService.primaryCommandLabel("   "));
    }

    @Test
    void blockedLabelMatch() {
        Set<String> blocked = Set.of("spawn", "home", "back", "hub");
        assertTrue(TeleportLockService.isBlockedLabel("spawn", blocked));
        assertTrue(TeleportLockService.isBlockedLabel("HOME", blocked));
        assertTrue(TeleportLockService.isBlockedLabel("hub", blocked));
        assertFalse(TeleportLockService.isBlockedLabel("msg", blocked));
        assertFalse(TeleportLockService.isBlockedLabel("", blocked));
    }

    @Test
    void teleportPureBlocksCrossWorldUnlessExempt() {
        // /spawn hub: cross-world, plugin cause
        assertTrue(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, true, false, false, true));
        // bypass / temp allow
        assertFalse(TeleportLockService.shouldBlockTeleportPure(
                true, true, false, true, false, false, true));
        // lock off
        assertFalse(TeleportLockService.shouldBlockTeleportPure(
                false, false, false, true, false, false, true));
        // nether portal cause exempt
        assertFalse(TeleportLockService.shouldBlockTeleportPure(
                true, false, true, true, false, false, true));
    }

    @Test
    void tempRtpAllowOnlyCoversPluginStyleNotCommand() {
        // PLUGIN cause mid short RTP window → allow
        assertFalse(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, true, true, true, false, false, true));
        // COMMAND cause mid short RTP window → still block (no /spawn free pass)
        assertTrue(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, true, false, true, false, false, true));
        // no temp allow, COMMAND cross-world → block
        assertTrue(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, false, false, true, false, false, true));
    }

    @Test
    void teleportPureSameWorldFreeZoneOnlyIfStayInside() {
        // same world, from free → to free: allow
        assertFalse(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, false, true, true, true));
        // same world, from free → to outside: block (no /warp escape)
        assertTrue(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, false, true, false, true));
        // same world, from wild → anywhere plugin: block
        assertTrue(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, false, false, true, true));
        assertTrue(TeleportLockService.shouldBlockTeleportPure(
                true, false, false, false, false, false, true));
    }

    @Test
    void worldChangePureRevertsUnlessLastSafeAlreadyInDest() {
        // illegal /spawn: last safe still in old world
        assertTrue(TeleportLockService.shouldBlockWorldChangePure(true, false, false));
        // allowed portal: last safe already updated to new world
        assertFalse(TeleportLockService.shouldBlockWorldChangePure(true, false, true));
        // bypass
        assertFalse(TeleportLockService.shouldBlockWorldChangePure(true, true, false));
        // lock off
        assertFalse(TeleportLockService.shouldBlockWorldChangePure(false, false, false));
    }

    @Test
    void temporaryAllowExpiresByTime() throws InterruptedException {
        TeleportLockService service = new TeleportLockService(null, null);
        UUID id = UUID.randomUUID();
        assertFalse(service.hasTemporaryAllow(id));
        service.allowTemporarily(id, 5_000L);
        assertTrue(service.hasTemporaryAllow(id));
        // merge keeps the later expiry (Math::max) — still active
        service.allowTemporarilyTicks(id, 1L);
        assertTrue(service.hasTemporaryAllow(id));
        UUID shortLived = UUID.randomUUID();
        service.allowTemporarilyTicks(shortLived, 1L); // 50ms floor
        Thread.sleep(80L);
        assertFalse(service.hasTemporaryAllow(shortLived));
    }

    @Test
    void temporaryAllowTicksIsShortWindow() {
        TeleportLockService service = new TeleportLockService(null, null);
        UUID id = UUID.randomUUID();
        service.allowTemporarilyTicks(id, 3L); // 150ms
        assertTrue(service.hasTemporaryAllow(id));
        service.clearTemporaryAllow(id);
        assertFalse(service.hasTemporaryAllow(id));
    }

    @Test
    void temporaryAllowMergeExtendsWindow() {
        TeleportLockService service = new TeleportLockService(null, null);
        UUID id = UUID.randomUUID();
        service.allowTemporarily(id, 50L);
        service.allowTemporarily(id, 60_000L);
        assertTrue(service.hasTemporaryAllow(id));
        service.pruneExpiredAllows();
        assertTrue(service.hasTemporaryAllow(id));
    }

    @Test
    void temporaryAllowNullSafe() {
        TeleportLockService service = new TeleportLockService(null, null);
        service.allowTemporarily((UUID) null, 1000L);
        assertFalse(service.hasTemporaryAllow((UUID) null));
        assertFalse(service.hasTemporaryAllow((org.bukkit.entity.Player) null));
    }

    @Test
    void clearPlayerDropsSafeAndTemp() {
        TeleportLockService service = new TeleportLockService(null, null);
        UUID id = UUID.randomUUID();
        service.allowTemporarily(id, 60_000L);
        assertTrue(service.hasTemporaryAllow(id));
        service.clearPlayer(id);
        assertFalse(service.hasTemporaryAllow(id));
        assertEquals(null, service.lastSafeLocation(id));
    }

    @Test
    void pluginStyleCauseDoesNotIncludeCommand() {
        assertTrue(TeleportLockService.isPluginStyleCause(
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertTrue(TeleportLockService.isPluginStyleCause(
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN));
        assertFalse(TeleportLockService.isPluginStyleCause(
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND));
        assertTrue(TeleportLockService.isPluginStyleCause(null));
    }

    @Test
    void revertAllowTicksIsOnlyAFew() {
        assertTrue(TeleportLockService.REVERT_ALLOW_TICKS >= 1L);
        assertTrue(TeleportLockService.REVERT_ALLOW_TICKS <= 5L);
    }
}
