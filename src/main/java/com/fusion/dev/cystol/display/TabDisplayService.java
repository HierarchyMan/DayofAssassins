package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventTimeline;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.bossbar.BarColor;
import me.neznamy.tab.api.bossbar.BarStyle;
import me.neznamy.tab.api.bossbar.BossBar;
import me.neznamy.tab.api.bossbar.BossBarManager;
import me.neznamy.tab.api.scoreboard.Scoreboard;
import me.neznamy.tab.api.scoreboard.ScoreboardManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TAB hard-depend display: bossbar + scoreboard lines (rendered via {@link EventDisplayRenderer}).
 * Memory snapshot drives pushes — lines rebuild only when text fingerprint changes; scoreboard
 * is shown once per player unless the board is cleared or content changes.
 */
public final class TabDisplayService {

    private static final String BOSSBAR_TITLE_SEED = "Day of Assassins";
    private static final String SCOREBOARD_NAME = "dayofassassins_sb";

    private final EventManager eventManager;
    private final KillService killService;
    private final PluginConfig config;
    private final Lang lang;
    private final Logger logger;

    private BossBar bossBar;
    private Scoreboard scoreboard;
    private boolean available = true;
    private boolean active;

    private String lastBossTitle;
    private float lastBossProgress = Float.NaN;
    private String lastScoreboardFingerprint;
    private final Set<UUID> scoreboardShown = ConcurrentHashMap.newKeySet();

    public TabDisplayService(
            EventManager eventManager,
            KillService killService,
            PluginConfig config,
            Lang lang,
            Logger logger
    ) {
        this.eventManager = eventManager;
        this.killService = killService;
        this.config = config;
        this.lang = lang;
        this.logger = logger;
    }

    public void init() {
        try {
            TabAPI api = TabAPI.getInstance();
            if (api == null) {
                available = false;
                logger.severe("TAB API instance is null");
                return;
            }
            BossBarManager bbm = api.getBossBarManager();
            if (bbm != null && config.tabBossbarEnabled()) {
                bossBar = bbm.createBossBar(BOSSBAR_TITLE_SEED, 0f, BarColor.RED, BarStyle.PROGRESS);
            }
            ScoreboardManager sbm = api.getScoreboardManager();
            if (sbm != null) {
                List<String> lines = EventDisplayRenderer.buildTemplateLines(config.scoreboardLines());
                if (!lines.isEmpty()) {
                    scoreboard = sbm.createScoreboard(
                            SCOREBOARD_NAME,
                            EventDisplayRenderer.colorAmpersandToSection("&cDay of Assassins"),
                            lines
                    );
                }
            }
        } catch (Throwable t) {
            available = false;
            logger.log(Level.SEVERE, "Failed to init TAB display", t);
        }
    }

    public void update(Instant now) {
        if (!available) {
            return;
        }
        try {
            EventTimeline timeline = eventManager.timeline();
            EventPhase phase = timeline.phaseAt(now);
            Optional<DenseRanking.Entry> top = killService.topKiller();
            String topName = top.map(DenseRanking.Entry::name).orElse(null);
            Integer topKills = top.map(DenseRanking.Entry::kills).orElse(null);

            EventDisplayRenderer.BossBarView bar = EventDisplayRenderer.renderBossBar(
                    timeline,
                    now,
                    config.announceLeadSeconds(),
                    lang.raw("bossbar.countdown-title"),
                    lang.raw("bossbar.title"),
                    lang.raw("bossbar.title-no-kills"),
                    topName,
                    topKills
            );

            active = true;
            if (bossBar != null) {
                String title = EventDisplayRenderer.colorAmpersandToSection(bar.titleLegacyAmpersand());
                float progress = bar.progress();
                if (!title.equals(lastBossTitle)) {
                    bossBar.setTitle(title);
                    lastBossTitle = title;
                }
                if (Float.isNaN(lastBossProgress) || Math.abs(progress - lastBossProgress) > 0.001f) {
                    bossBar.setProgress(progress);
                    lastBossProgress = progress;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    TabPlayer tp = TabAPI.getInstance().getPlayer(p.getUniqueId());
                    if (tp != null && !bossBar.containsPlayer(tp)) {
                        bossBar.addPlayer(tp);
                    }
                }
            }

            if (scoreboard != null) {
                EventDisplayRenderer.ScoreboardView sb = EventDisplayRenderer.renderScoreboardLines(
                        config.scoreboardLines(),
                        phase,
                        topName,
                        topKills == null ? 0 : topKills
                );
                String fingerprint = scoreboardFingerprint(phase, topName, topKills == null ? 0 : topKills, sb);
                boolean contentChanged = !fingerprint.equals(lastScoreboardFingerprint);
                if (contentChanged) {
                    try {
                        List<?> current = scoreboard.getLines();
                        int size = current == null ? 0 : current.size();
                        for (int i = size - 1; i >= 0; i--) {
                            scoreboard.removeLine(i);
                        }
                        for (String line : sb.linesSectionColor()) {
                            scoreboard.addLine(line == null || line.isBlank() ? " " : line);
                        }
                    } catch (Throwable t) {
                        logger.log(Level.FINE, "scoreboard line refresh failed", t);
                    }
                    lastScoreboardFingerprint = fingerprint;
                    // Content change: re-show so TAB clients refresh line payloads.
                    scoreboardShown.clear();
                }
                ScoreboardManager sbm = TabAPI.getInstance().getScoreboardManager();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    if (scoreboardShown.contains(id)) {
                        continue;
                    }
                    TabPlayer tp = TabAPI.getInstance().getPlayer(id);
                    if (tp != null && sbm != null) {
                        sbm.showScoreboard(tp, scoreboard);
                        scoreboardShown.add(id);
                    }
                }
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "TAB update failed", t);
        }
    }

    private static String scoreboardFingerprint(
            EventPhase phase,
            String topName,
            int topKills,
            EventDisplayRenderer.ScoreboardView sb
    ) {
        StringBuilder b = new StringBuilder(64);
        b.append(phase == null ? "IDLE" : phase.name()).append('|');
        b.append(topName == null ? "" : topName).append('|').append(topKills).append('|');
        for (String line : sb.linesSectionColor()) {
            b.append(line == null ? "" : line).append('\n');
        }
        return b.toString();
    }

    public void clear() {
        if (!available || !active) {
            return;
        }
        active = false;
        lastBossTitle = null;
        lastBossProgress = Float.NaN;
        lastScoreboardFingerprint = null;
        scoreboardShown.clear();
        try {
            if (bossBar != null) {
                for (TabPlayer tp : new ArrayList<>(bossBar.getPlayers())) {
                    bossBar.removePlayer(tp);
                }
            }
            if (scoreboard != null) {
                ScoreboardManager sbm = TabAPI.getInstance().getScoreboardManager();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    TabPlayer tp = TabAPI.getInstance().getPlayer(p.getUniqueId());
                    if (tp != null && sbm != null) {
                        sbm.resetScoreboard(tp);
                    }
                }
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "TAB clear failed", t);
        }
    }

    /** Drop scoreboard-shown flag so next update re-applies for joiners mid-event. */
    public void onPlayerQuit(UUID uuid) {
        if (uuid != null) {
            scoreboardShown.remove(uuid);
        }
    }

    /** Test/diag: whether TAB objects were created (null API → false). */
    public boolean isAvailable() {
        return available;
    }

    public boolean hasBossBar() {
        return bossBar != null;
    }

    public boolean hasScoreboard() {
        return scoreboard != null;
    }
}
