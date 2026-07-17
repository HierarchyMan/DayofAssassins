package com.fusion.dev.cystol;

import com.fusion.dev.cystol.arena.ArenaWandListener;
import com.fusion.dev.cystol.arena.FfaSpawnService;
import com.fusion.dev.cystol.ceremony.EndCeremonyService;
import com.fusion.dev.cystol.command.EventCommand;
import com.fusion.dev.cystol.command.PaperCommandRegistrar;
import com.fusion.dev.cystol.command.PrecivCommand;
import com.fusion.dev.cystol.compass.CompassGui;
import com.fusion.dev.cystol.compass.CompassKeys;
import com.fusion.dev.cystol.compass.CompassListener;
import com.fusion.dev.cystol.compass.CompassService;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.display.TabDisplayService;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventScheduler;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.kill.PvpManagerKillListener;
import com.fusion.dev.cystol.storage.EventRepository;
import com.fusion.dev.cystol.storage.KillRepository;
import com.fusion.dev.cystol.storage.SqliteDatabase;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Paper-only plugin (loaded via {@code paper-plugin.yml}, not Bukkit {@code plugin.yml}).
 */
public final class DayOfAssassinsPlugin extends JavaPlugin {

    private SqliteDatabase database;
    private EventManager eventManager;
    private KillService killService;
    private EventScheduler eventScheduler;
    private TabDisplayService tabDisplayService;
    private CompassService compassService;

    private PluginConfig config;
    private Lang lang;
    private PrecivCommand precivCommand;
    private EventCommand eventCommand;

    @Override
    public void onLoad() {
        registerPermissions();
        config = new PluginConfig(this);
        config.loadDefaults();
        lang = new Lang(this);
        lang.load();
        // Must bind COMMANDS lifecycle before the server freezes the tree
        PaperCommandRegistrar.register(this, () -> eventCommand, () -> precivCommand);
    }

    @Override
    public void onEnable() {
        if (config == null) {
            config = new PluginConfig(this);
            config.loadDefaults();
        }
        if (lang == null) {
            lang = new Lang(this);
            lang.load();
        }

        database = new SqliteDatabase(this, config.storageFile());
        try {
            database.open();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to open SQLite — disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        EventRepository eventRepository = new EventRepository(database);
        KillRepository killRepository = new KillRepository(database);

        eventManager = new EventManager(config, eventRepository, getLogger());
        eventManager.loadFromStorageAndConfig();

        killService = new KillService(killRepository, getLogger());
        killService.load();

        EffectService effects = new EffectService(config, getLogger());
        CompassKeys keys = new CompassKeys(this);
        compassService = new CompassService(config, lang, keys);
        CompassGui compassGui = new CompassGui(compassService, killService, lang, effects);

        FfaSpawnService ffaSpawnService = new FfaSpawnService(config, getLogger());
        EndCeremonyService ceremonyService = new EndCeremonyService(killService, lang, effects, getLogger());

        tabDisplayService = new TabDisplayService(eventManager, killService, config, lang, getLogger());
        tabDisplayService.init();

        CompassListener compassListener = new CompassListener(
                this, compassService, compassGui, eventManager, lang, config, effects
        );

        eventScheduler = new EventScheduler(
                this, eventManager, config, lang, ffaSpawnService, compassListener,
                ceremonyService, tabDisplayService, effects, getLogger()
        );

        getServer().getPluginManager().registerEvents(compassListener, this);
        getServer().getPluginManager().registerEvents(new ArenaWandListener(compassService, config, lang), this);
        getServer().getPluginManager().registerEvents(
                new PvpManagerKillListener(eventManager, killService, effects, getLogger()), this);

        getServer().getScheduler().runTaskTimer(this, () -> {
            for (var p : getServer().getOnlinePlayers()) {
                compassService.tickTracking(p);
            }
        }, 20L, 20L);

        eventCommand = new EventCommand(lang);
        precivCommand = new PrecivCommand(eventManager, killService, compassService, config, lang);

        eventScheduler.start();
        try {
            eventRepository.setMetric("last_enable", Instant.now().toString());
            eventRepository.setMetric("phase", eventManager.phase().name());
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Metric write failed", e);
        }

        getLogger().info("Day of Assassins enabled (Paper plugin). Phase=" + eventManager.phase());
    }

    @Override
    public void onDisable() {
        if (eventScheduler != null) {
            eventScheduler.stop();
        }
        if (tabDisplayService != null) {
            tabDisplayService.clear();
        }
        if (eventManager != null) {
            eventManager.persist();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("Day of Assassins disabled.");
    }

    private void registerPermissions() {
        PluginManager pm = Bukkit.getPluginManager();
        addPerm(pm, "preciv.use", "Base /preciv access", PermissionDefault.TRUE);
        addPerm(pm, "preciv.event", "Use /event", PermissionDefault.TRUE);
        addPerm(pm, "preciv.compass", "Use /preciv compass", PermissionDefault.TRUE);
        addPerm(pm, "preciv.killtop", "View kill leaderboard", PermissionDefault.TRUE);
        addPerm(pm, "preciv.admin", "Admin setup commands", PermissionDefault.OP);
        addPerm(pm, "preciv.ffa.tp.bypass", "Skip FFA mass teleport", PermissionDefault.FALSE);
    }

    private static void addPerm(PluginManager pm, String name, String desc, PermissionDefault def) {
        if (pm.getPermission(name) == null) {
            pm.addPermission(new Permission(name, desc, def));
        }
    }

    public EventManager eventManager() {
        return eventManager;
    }

    public KillService killService() {
        return killService;
    }
}
