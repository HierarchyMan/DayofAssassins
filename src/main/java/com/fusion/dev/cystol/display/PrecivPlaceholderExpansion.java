package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.storage.PastGameRepository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PlaceholderAPI soft-depend placeholders ({@code %preciv_*%}):
 * <ul>
 *   <li>Live: {@code top_1_name|kills|place}, {@code kills}, {@code place}</li>
 *   <li>Last past game: {@code prev_top_1_name|kills|place}, {@code last_game_id}, {@code game_count}</li>
 *   <li>Past game N (chronological id): {@code game_3_top_1_name|kills|place}, {@code game_3_id}</li>
 * </ul>
 * Also accepts {@code top1_name} (no underscore after top).
 */
public final class PrecivPlaceholderExpansion {

    public static final int MAX_TOP_INDEX = 50;
    public static final int MAX_GAME_ID = 1_000_000;

    private final JavaPlugin plugin;
    private final KillService killService;
    private final PluginConfig config;
    private final Logger logger;
    private PrecivPapiExpansion registered;

    public PrecivPlaceholderExpansion(JavaPlugin plugin, KillService killService, PluginConfig config) {
        this.plugin = plugin;
        this.killService = killService;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    public void tryRegister() {
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            logger.info("PlaceholderAPI not present — %preciv_top_N_* placeholders unavailable");
            return;
        }
        try {
            PrecivPapiExpansion exp = new PrecivPapiExpansion(plugin, this);
            if (exp.register()) {
                registered = exp;
                logger.info("Registered PlaceholderAPI expansion %preciv_top_* / prev_* / game_N_*");
            } else {
                logger.warning("PlaceholderAPI expansion register() returned false");
            }
        } catch (NoClassDefFoundError | Exception e) {
            logger.log(Level.WARNING, "Could not register PlaceholderAPI expansion", e);
            registered = null;
        }
    }

    public void unregister() {
        if (registered != null) {
            try {
                registered.unregister();
            } catch (NoClassDefFoundError | Exception ignored) {
                // best-effort
            }
            registered = null;
        }
    }

    /**
     * Runtime resolver wired to {@link KillService}.
     */
    public String resolve(String params, UUID viewerUuid) {
        List<DenseRanking.Entry> live = killService.ranking();
        String emptyN = config.scoreboardEmptyName();
        String emptyK = config.scoreboardEmptyKills();

        // Past-game meta + tops (need KillService for history)
        String past = resolvePast(params, emptyN, emptyK);
        if (past != null) {
            return past;
        }

        return resolve(
                params,
                viewerUuid,
                live,
                uuid -> killService.getKills(uuid),
                emptyN,
                emptyK
        );
    }

    /**
     * Past-game placeholders. Returns null if params are not past-game related.
     */
    String resolvePast(String params, String emptyName, String emptyKills) {
        if (params == null || params.isBlank()) {
            return null;
        }
        String p = params.trim().toLowerCase(Locale.ROOT);
        String emptyN = emptyName == null || emptyName.isBlank() ? "—" : emptyName;
        String emptyK = emptyKills == null ? "0" : emptyKills;

        if (p.equals("last_game_id") || p.equals("prev_game_id")) {
            return killService.latestPastGameId()
                    .stream().mapToObj(String::valueOf).findFirst().orElse("0");
        }
        if (p.equals("game_count") || p.equals("past_game_count")) {
            return String.valueOf(killService.pastGameCount());
        }

        // prev_top_<n>_*  OR  prev_top<n>_*  → latest past game
        if (p.startsWith("prev_top") || p.startsWith("prevtop")) {
            String rest = p.startsWith("prev_top") ? p.substring("prev_top".length()) : p.substring("prevtop".length());
            Optional<PastGameRepository.PastGame> latest = killService.latestPastGame();
            List<DenseRanking.Entry> ranking = latest.map(PastGameRepository.PastGame::ranking).orElse(List.of());
            return resolveTopField(rest, ranking, emptyN, emptyK);
        }

        // game_<id>_top_<n>_*  OR  game_<id>_top<n>_*
        if (p.startsWith("game_")) {
            String rest = p.substring("game_".length());
            int us = rest.indexOf('_');
            if (us <= 0) {
                // game_<id> alone → echo id if exists, else 0
                try {
                    int id = Integer.parseInt(rest);
                    if (id < 1 || id > MAX_GAME_ID) {
                        return null;
                    }
                    return killService.pastGame(id).isPresent() ? String.valueOf(id) : "0";
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            String idStr = rest.substring(0, us);
            String afterId = rest.substring(us + 1);
            int gameId;
            try {
                gameId = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                return null;
            }
            if (gameId < 1 || gameId > MAX_GAME_ID) {
                return null;
            }
            if (afterId.equals("id")) {
                return killService.pastGame(gameId).isPresent() ? String.valueOf(gameId) : "0";
            }
            if (afterId.equals("ended") || afterId.equals("ended_at")) {
                return killService.pastGame(gameId)
                        .map(g -> String.valueOf(g.endedAtEpochSeconds()))
                        .orElse("0");
            }
            if (!afterId.startsWith("top")) {
                return null;
            }
            String topRest = afterId.substring(3); // after "top"
            List<DenseRanking.Entry> ranking = killService.pastGame(gameId)
                    .map(PastGameRepository.PastGame::ranking)
                    .orElse(List.of());
            return resolveTopField(topRest, ranking, emptyN, emptyK);
        }

        return null;
    }

    /**
     * Parse {@code _1_name}, {@code 1_name}, {@code _1_kills} after a "top" prefix.
     */
    private static String resolveTopField(
            String afterTop,
            List<DenseRanking.Entry> ranking,
            String emptyN,
            String emptyK
    ) {
        if (afterTop == null) {
            return null;
        }
        String rest = afterTop;
        if (rest.startsWith("_")) {
            rest = rest.substring(1);
        }
        int us = rest.indexOf('_');
        if (us <= 0 || us >= rest.length() - 1) {
            return null;
        }
        String indexStr = rest.substring(0, us);
        String field = rest.substring(us + 1);
        int index;
        try {
            index = Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 1 || index > MAX_TOP_INDEX) {
            return null;
        }
        List<DenseRanking.Entry> list = ranking == null ? List.of() : ranking;
        DenseRanking.Entry entry = index <= list.size() ? list.get(index - 1) : null;
        return switch (field) {
            case "name" -> entry == null || entry.name() == null || entry.name().isBlank()
                    ? emptyN
                    : entry.name();
            case "kills" -> entry == null ? emptyK : String.valueOf(entry.kills());
            case "place" -> entry == null ? String.valueOf(index) : String.valueOf(entry.place());
            default -> null;
        };
    }

    /**
     * Pure live placeholder resolve (unit-testable).
     *
     * @return resolved string, or {@code null} if params unknown
     */
    public static String resolve(
            String params,
            UUID viewerUuid,
            List<DenseRanking.Entry> ranking,
            ToIntFunction<UUID> killsOf,
            String emptyName,
            String emptyKills
    ) {
        if (params == null || params.isBlank()) {
            return "";
        }
        String p = params.trim().toLowerCase(Locale.ROOT);
        String emptyN = emptyName == null || emptyName.isBlank() ? "—" : emptyName;
        String emptyK = emptyKills == null ? "0" : emptyKills;
        List<DenseRanking.Entry> list = ranking == null ? List.of() : ranking;

        if (p.equals("kills")) {
            if (viewerUuid == null) {
                return emptyK;
            }
            return String.valueOf(killsOf == null ? 0 : killsOf.applyAsInt(viewerUuid));
        }
        if (p.equals("place")) {
            if (viewerUuid == null) {
                return "0";
            }
            return String.valueOf(placeOf(list, viewerUuid));
        }

        // top_<n>_field  OR  top<n>_field
        if (!p.startsWith("top")) {
            return null;
        }
        String rest = p.substring(3);
        return resolveTopField(rest, list, emptyN, emptyK);
    }

    private static int placeOf(List<DenseRanking.Entry> ranking, UUID uuid) {
        for (DenseRanking.Entry e : ranking) {
            if (e.uuid().equals(uuid)) {
                return e.place();
            }
        }
        return 0;
    }
}
