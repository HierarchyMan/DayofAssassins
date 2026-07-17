package com.fusion.dev.cystol.ceremony;

import com.fusion.dev.cystol.config.Lang;
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
    private final Lang lang;
    private final EffectService effects;
    private final Logger logger;

    public EndCeremonyService(KillService killService, Lang lang, EffectService effects, Logger logger) {
        this.killService = killService;
        this.lang = lang;
        this.effects = effects;
        this.logger = logger;
    }

    public void runCeremony() {
        List<DenseRanking.Entry> ranking = killService.ranking();
        logger.info("Running end ceremony for " + ranking.size() + " ranked players");

        Map<UUID, DenseRanking.Entry> byId = new HashMap<>();
        for (DenseRanking.Entry e : ranking) {
            byId.put(e.uuid(), e);
        }

        // Online players: show their place (0 kills => not in ranking unless we include them)
        // Design: offline count as winners too; everyone with a kill row is ranked.
        // Online with 0 kills still get place-other with place = ranking size + 1 if not present
        int maxPlace = ranking.stream().mapToInt(DenseRanking.Entry::place).max().orElse(0);

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
}
