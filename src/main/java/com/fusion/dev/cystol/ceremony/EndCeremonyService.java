package com.fusion.dev.cystol.ceremony;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class EndCeremonyService {

    private final KillService killService;
    private final PluginConfig config;
    private final Lang lang;
    private final EffectService effects;
    private final Logger logger;

    public EndCeremonyService(
            KillService killService,
            PluginConfig config,
            Lang lang,
            EffectService effects,
            Logger logger
    ) {
        this.killService = killService;
        this.config = config;
        this.lang = lang;
        this.effects = effects;
        this.logger = logger;
    }

    public void runCeremony() {
        // Single frozen ranking snapshot for the whole ceremony (cached in KillService).
        List<DenseRanking.Entry> ranking = killService.ranking();
        logger.info("Running end ceremony for " + ranking.size() + " ranked players");

        Map<UUID, DenseRanking.Entry> byId = new HashMap<>(Math.max(16, ranking.size() * 2));
        int maxPlace = 0;
        for (DenseRanking.Entry e : ranking) {
            byId.put(e.uuid(), e);
            if (e.place() > maxPlace) {
                maxPlace = e.place();
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            DenseRanking.Entry entry = byId.get(player.getUniqueId());
            int place;
            int kills;
            if (entry != null) {
                place = entry.place();
                kills = entry.kills();
            } else {
                place = maxPlace + 1;
                kills = 0;
                if (ranking.isEmpty()) {
                    place = 1;
                }
            }
            showPlace(player, place, kills);
        }

        deliverRewards(ranking);
    }

    private void showPlace(Player player, int place, int kills) {
        String key = switch (place) {
            case 1 -> "end.title.place-1";
            case 2 -> "end.title.place-2";
            case 3 -> "end.title.place-3";
            default -> "end.title.place-other";
        };
        Map<String, String> ph = Map.of(
                "place", String.valueOf(place),
                "kills", String.valueOf(kills),
                "player", player.getName()
        );
        Title title = Title.title(
                lang.msg(key + ".title", ph),
                lang.msg(key + ".subtitle", ph),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(1))
        );
        player.showTitle(title);
        if (place >= 1 && place <= 3) {
            effects.play(player, EffectService.EffectKey.END_TOP3);
        } else {
            effects.play(player, EffectService.EffectKey.END_NORMAL);
        }
    }

    private void deliverRewards(List<DenseRanking.Entry> ranking) {
        if (!config.rewardsEnabled()) {
            return;
        }
        int maxRewardPlace = config.rewardsMaxPlace();
        if (maxRewardPlace <= 0) {
            return;
        }
        List<DenseRanking.Entry> eligible = RewardEligibility.eligible(ranking, maxRewardPlace);
        if (eligible.isEmpty()) {
            logger.info("Rewards enabled but no players with place <= " + maxRewardPlace);
            return;
        }

        String maxPlaceStr = String.valueOf(maxRewardPlace);
        StringBuilder audit = new StringBuilder("Reward-eligible (place <= ")
                .append(maxRewardPlace).append("): ");
        for (int i = 0; i < eligible.size(); i++) {
            DenseRanking.Entry e = eligible.get(i);
            if (i > 0) {
                audit.append(", ");
            }
            audit.append('#').append(e.place()).append(' ')
                    .append(e.name() == null ? "?" : e.name())
                    .append(" (").append(e.kills()).append(" kills)");
        }
        logger.info(audit.toString());

        Map<UUID, DenseRanking.Entry> eligibleById = new HashMap<>(eligible.size() * 2);
        for (DenseRanking.Entry e : eligible) {
            eligibleById.put(e.uuid(), e);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            DenseRanking.Entry entry = eligibleById.get(player.getUniqueId());
            if (entry == null) {
                continue;
            }
            Map<String, String> ph = Map.of(
                    "place", String.valueOf(entry.place()),
                    "kills", String.valueOf(entry.kills()),
                    "player", player.getName(),
                    "max_place", maxPlaceStr
            );
            player.sendMessage(lang.msg("rewards.eligible", ph));
        }

        // Staff summary (online with admin perm) for claim workflow — includes offline winners by name.
        Map<String, String> headerPh = Map.of("max_place", maxPlaceStr);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission("preciv.admin")) {
                continue;
            }
            staff.sendMessage(lang.msg("rewards.staff-summary-header", headerPh));
            for (DenseRanking.Entry e : eligible) {
                staff.sendMessage(lang.msg("rewards.staff-summary-entry", Map.of(
                        "place", String.valueOf(e.place()),
                        "kills", String.valueOf(e.kills()),
                        "player", e.name() == null ? "?" : e.name(),
                        "max_place", maxPlaceStr
                )));
            }
        }
    }
}
