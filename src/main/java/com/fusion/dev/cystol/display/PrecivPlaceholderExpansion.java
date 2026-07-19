package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Positioned leaderboard placeholders (via PlaceholderAPI soft-depend):
 * <ul>
 *   <li>{@code %preciv_top_1_name%} {@code %preciv_top_1_kills%} {@code %preciv_top_1_place%}</li>
 *   <li>… through N (max 50)</li>
 *   <li>{@code %preciv_kills%} / {@code %preciv_place%} for the viewing player</li>
 * </ul>
 * Also accepts {@code top1_name} (no underscore after top).
 */
public final class PrecivPlaceholderExpansion {

    public static final int MAX_TOP_INDEX = 50;

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
                logger.info("Registered PlaceholderAPI expansion %preciv_top_<n>_name|kills|place%");
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
        List<DenseRanking.Entry> ranking = killService.ranking();
        return resolve(
                params,
                viewerUuid,
                ranking,
                uuid -> killService.getKills(uuid),
                config.scoreboardEmptyName(),
                config.scoreboardEmptyKills()
        );
    }

    /**
     * Pure placeholder resolve (unit-testable).
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

    private static int placeOf(List<DenseRanking.Entry> ranking, UUID uuid) {
        for (DenseRanking.Entry e : ranking) {
            if (e.uuid().equals(uuid)) {
                return e.place();
            }
        }
        return 0;
    }
}
