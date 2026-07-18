package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.event.EventPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure gates for hunt spawn RTP: live window, kickoff phase change, cuboid match.
 * No Bukkit / BetterRTP.
 */
class SpawnHuntRtpServiceTest {

    private static final CuboidBounds SPAWN = new CuboidBounds(0, 64, 0, 20, 80, 20);

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
        // cosmetic grace is still COUNTDOWN → same path
        assertTrue(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.COUNTDOWN, EventPhase.HUNT));

        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.PAUSED, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.HUNT, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.HUNT, EventPhase.FFA));
        // Schedule rewind FFA→HUNT is a real re-enter of hunt (flags cleared with schedule)
        assertTrue(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.FFA, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.COUNTDOWN, EventPhase.FFA));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.COUNTDOWN, EventPhase.PAUSED));
    }

    @Test
    void matchesSpawnCuboidRequiresConfiguredWorldAndInsideBounds() {
        assertTrue(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 10, 70, 10));
        // inclusive corners
        assertTrue(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 0, 64, 0));
        assertTrue(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 20, 80, 20));

        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                false, "world", SPAWN, "world", 10, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world_nether", 10, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 50, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 10, 100, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, "world", 10, 50, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, null, SPAWN, "world", 10, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", SPAWN, null, 10, 70, 10));
        assertFalse(SpawnHuntRtpService.matchesSpawnCuboid(
                true, "world", null, "world", 10, 70, 10));
    }

    @Test
    void joinRtpUsesSameLiveWindowAsForceNotKickoff() {
        // Join path = live window only (HUNT + unpaused + enabled)
        // Not the kickoff phase-change gate — so unpause mid-hunt does not mass-dump,
        // but a join during live HUNT still RTPs if in cuboid.
        assertTrue(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.HUNT));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.PAUSED, EventPhase.HUNT));

        // FFA join must never RTP via these gates
        assertFalse(SpawnHuntRtpService.isLiveHuntRtpWindow(true, false, EventPhase.FFA));
        assertFalse(SpawnHuntRtpService.shouldKickoffOnPhaseChange(EventPhase.HUNT, EventPhase.FFA));
    }
}
