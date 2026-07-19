package com.fusion.dev.cystol.teleport;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.util.TimeUtil;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hunt-only ownership of {@code /rtp} when {@code rtp.command-override} is true.
 * Cooldown applies only to this path (not kickoff / zone / respawn force RTP).
 */
public final class RtpCommandService {

    private final EventManager eventManager;
    private final PluginConfig config;
    private final SpawnHuntRtpService spawnHuntRtp;
    private final Lang lang;
    /** uuid → epoch ms when next /rtp is allowed */
    private final ConcurrentHashMap<UUID, Long> nextAllowedMs = new ConcurrentHashMap<>();

    public RtpCommandService(
            EventManager eventManager,
            PluginConfig config,
            SpawnHuntRtpService spawnHuntRtp,
            Lang lang
    ) {
        this.eventManager = eventManager;
        this.config = config;
        this.spawnHuntRtp = spawnHuntRtp;
        this.lang = lang;
    }

    /**
     * Whether we should intercept this command label during HUNT (caller still cancels the event).
     */
    public boolean shouldIntercept(String label) {
        if (!config.rtpCommandOverride()) {
            return false;
        }
        if (label == null || !label.equalsIgnoreCase("rtp")) {
            return false;
        }
        return eventManager.phase() == EventPhase.HUNT && !eventManager.isPaused();
    }

    /**
     * Handle intercepted /rtp. Always message the player; never run native BetterRTP command.
     */
    public void handle(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (eventManager.phase() != EventPhase.HUNT || eventManager.isPaused()) {
            return;
        }
        if (!config.rtpCommandAllow()) {
            player.sendMessage(lang.msg("rtp.command-disabled"));
            return;
        }
        long now = System.currentTimeMillis();
        Long next = nextAllowedMs.get(player.getUniqueId());
        if (next != null && now < next) {
            long remainSec = Math.max(1L, (next - now + 999L) / 1000L);
            player.sendMessage(lang.msg("rtp.command-cooldown", Map.of(
                    "seconds", String.valueOf(remainSec),
                    "time", TimeUtil.formatCountdown(remainSec)
            )));
            return;
        }
        boolean ok = spawnHuntRtp.forceRtpPlayer(player);
        if (!ok) {
            player.sendMessage(lang.msg("rtp.command-failed"));
            return;
        }
        long cdSec = config.rtpCommandCooldownSeconds();
        if (cdSec > 0) {
            nextAllowedMs.put(player.getUniqueId(), now + cdSec * 1000L);
        }
        player.sendMessage(lang.msg("rtp.command-ok"));
    }

    public void clearPlayer(UUID uuid) {
        if (uuid != null) {
            nextAllowedMs.remove(uuid);
        }
    }

    public void clearAll() {
        nextAllowedMs.clear();
    }
}
