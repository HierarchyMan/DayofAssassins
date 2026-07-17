package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventTimeline;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.util.TextUtil;
import com.fusion.dev.cystol.util.TimeUtil;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TAB hard-depend display: bossbar + scoreboard lines.
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
                List<String> lines = buildLineTemplates();
                if (!lines.isEmpty()) {
                    scoreboard = sbm.createScoreboard(SCOREBOARD_NAME, colorAmpersandToSection("&cDay of Assassins"), lines);
                }
            }
        } catch (Throwable t) {
            available = false;
            logger.log(Level.SEVERE, "Failed to init TAB display", t);
        }
    }

    private List<String> buildLineTemplates() {
        List<String> lines = new ArrayList<>();
        for (PluginConfig.ScoreboardLine line : config.scoreboardLines()) {
            while (lines.size() <= line.line()) {
                lines.add(" ");
            }
            lines.set(line.line(), colorAmpersandToSection(line.text()));
        }
        // remove leading empties carefully — TAB may not like empty list
        return lines;
    }

    public void update(Instant now) {
        if (!available) {
            return;
        }
        try {
            EventTimeline timeline = eventManager.timeline();
            EventPhase phase = timeline.phaseAt(now);
            String title;
            float progress;
            if (phase == EventPhase.COUNTDOWN) {
                long secs = eventManager.start()
                        .map(s -> Math.max(0, s.getEpochSecond() - now.getEpochSecond()))
                        .orElse(0L);
                String countdown = TimeUtil.formatCountdown(secs);
                title = TextUtil.apply(lang.raw("bossbar.countdown-title"), Map.of("countdown", countdown));
                progress = (float) timeline.countdownFillProgress(now, config.announceLeadSeconds());
            } else {
                Optional<DenseRanking.Entry> top = killService.topKiller();
                if (top.isPresent()) {
                    title = TextUtil.apply(lang.raw("bossbar.title"), Map.of(
                            "top_killer", top.get().name() == null ? "?" : top.get().name(),
                            "top_kills", String.valueOf(top.get().kills())
                    ));
                } else {
                    title = TextUtil.apply(lang.raw("bossbar.title-no-kills"), Map.of());
                }
                progress = (float) timeline.liveFillProgress(now);
            }

            active = true;
            if (bossBar != null) {
                bossBar.setTitle(colorAmpersandToSection(title));
                bossBar.setProgress(Math.max(0f, Math.min(1f, progress)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    TabPlayer tp = TabAPI.getInstance().getPlayer(p.getUniqueId());
                    if (tp != null && !bossBar.containsPlayer(tp)) {
                        bossBar.addPlayer(tp);
                    }
                }
            }

            if (scoreboard != null) {
                Map<String, String> ph = new HashMap<>();
                Optional<DenseRanking.Entry> top = killService.topKiller();
                ph.put("top_killer", top.map(DenseRanking.Entry::name).orElse("—"));
                ph.put("top_kills", top.map(e -> String.valueOf(e.kills())).orElse("0"));
                ph.put("phase", phase.name());
                // update lines by remove/add (API has no setLines on 5.0.7)
                try {
                    List<?> current = scoreboard.getLines();
                    int size = current == null ? 0 : current.size();
                    for (int i = size - 1; i >= 0; i--) {
                        scoreboard.removeLine(i);
                    }
                    List<String> rendered = new ArrayList<>();
                    int maxIdx = -1;
                    for (PluginConfig.ScoreboardLine line : config.scoreboardLines()) {
                        maxIdx = Math.max(maxIdx, line.line());
                    }
                    for (int i = 0; i <= maxIdx; i++) {
                        rendered.add(" ");
                    }
                    for (PluginConfig.ScoreboardLine line : config.scoreboardLines()) {
                        rendered.set(line.line(), colorAmpersandToSection(TextUtil.apply(line.text(), ph)));
                    }
                    for (String line : rendered) {
                        scoreboard.addLine(line == null || line.isBlank() ? " " : line);
                    }
                } catch (Throwable t) {
                    logger.log(Level.FINE, "scoreboard line refresh failed", t);
                }
                ScoreboardManager sbm = TabAPI.getInstance().getScoreboardManager();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    TabPlayer tp = TabAPI.getInstance().getPlayer(p.getUniqueId());
                    if (tp != null && sbm != null) {
                        sbm.showScoreboard(tp, scoreboard);
                    }
                }
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "TAB update failed", t);
        }
    }

    public void clear() {
        if (!available || !active) {
            return;
        }
        active = false;
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

    private static String colorAmpersandToSection(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('&', '§');
    }
}
