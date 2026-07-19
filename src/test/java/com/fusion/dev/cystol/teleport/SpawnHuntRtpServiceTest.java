package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.event.EventPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure gates for hunt RTP: live window, kickoff phase change, spawn cuboid OR arena cuboid.
 * No Bukkit / BetterRTP.
 */
class SpawnHuntRtpServiceTest {

    private static final CuboidBounds SPAWN = new CuboidBounds(0, 64, 0, 20, 80, 20);
    private static final CuboidBounds ARENA = new CuboidBounds(100, 64, 100, 200, 90, 200);

    @Test
    void liveHuntWindowRequiresEnabledUnpausedHuntOnly() {
        assertTrue(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.HUNT));

        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(false, false, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, true, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.FFA));
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.COUNTDOWN));
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.PAUSED));
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.IDLE));
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.ENDED));
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, null));
    }

    @Test
    void kickoffOnlyOnEnterHuntFromPreEventNotPausedRecovery() {
        assertTrue(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.COUNTDOWN, EventPhase.HUNT));
        assertTrue(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.IDLE, EventPhase.HUNT));
        assertTrue(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.COUNTDOWN, EventPhase.HUNT));

        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.PAUSED, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.HUNT, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.HUNT, EventPhase.FFA));
        assertTrue(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.FFA, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.COUNTDOWN, EventPhase.FFA));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.COUNTDOWN, EventPhase.PAUSED));
    }

    @Test
    void matchesSpawnCuboidRequiresConfiguredWorldAndInsideBounds() {
        assertTrue(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 10, 70, 10));
        assertTrue(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "WORLD", 0, 64, 0));

        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                false, "world", SPAWN, "world", 10, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world_nether", 10, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 50, 70, 10));
    }

    @Test
    void matchesArenaCuboidWorldAndBounds() {
        assertTrue(SpawnHuntRtpService.matchesArenaCuboid(
                "world", ARENA, "world", 150, 70, 150));
        assertFalse(SpawnHuntRtpService.matchesArenaCuboid(
                "world", ARENA, "world", 10, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesArenaCuboid(
                "world", ARENA, "other", 150, 70, 150));
        assertFalse(SpawnHuntRtpService.matchesArenaCuboid(
                "world", null, "world", 150, 70, 150));
    }

    @Test
    void matchesEvictZoneIsSpawnOrArenaSameWorldOk() {
        // spawn region
        assertTrue(SpawnHuntRtpService.matchesEvictZone(
                true, "world", SPAWN, "world", ARENA, "world", 10, 70, 10));
        // arena (FFA cuboid) — owner case: camping pvparena during hunt
        assertTrue(SpawnHuntRtpService.matchesEvictZone(
                true, "world", SPAWN, "world", ARENA, "world", 150, 70, 150));
        // outside both
        assertFalse(SpawnHuntRtpService.matchesEvictZone(
                true, "world", SPAWN, "world", ARENA, "world", 50, 70, 50));
        // spawn unconfigured → arena still works
        assertTrue(SpawnHuntRtpService.matchesEvictZone(
                false, "world", SPAWN, "world", ARENA, "world", 150, 70, 150));
        assertFalse(SpawnHuntRtpService.matchesEvictZone(
                false, "world", SPAWN, "world", ARENA, "world", 10, 70, 10));
    }

    @Test
    void ffaPhaseNeverInLiveWindow() {
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.FFA));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.HUNT, EventPhase.FFA));
    }

    @Test
    void noBypassToggleIsMemoryOnly() {
        NoBypassService nb = new NoBypassService(true);
        assertTrue(nb.isActive());
        assertFalse(nb.toggle());
        assertFalse(nb.isActive());
        assertTrue(nb.toggle());
        assertTrue(nb.isActive());
        nb.setActive(false);
        assertFalse(nb.isActive());
    }
}
