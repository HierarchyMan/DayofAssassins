package com.fusion.dev.cystol;

import com.fusion.dev.cystol.arena.ArenaWandListener;
import com.fusion.dev.cystol.arena.FfaSpawnService;
import com.fusion.dev.cystol.ceremony.EndCeremonyService;
import com.fusion.dev.cystol.command.EventCommand;
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
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Instant;
import java.util.logging.Level;

public final class DayOfAssassinsPlugin extends JavaPlugin {

    private SqliteDatabase database;
    private EventManager eventManager;
    private KillService killService;
    private EventScheduler eventScheduler;
    private TabDisplayService tabDisplayService;
    private CompassService compassService;

    @Override
    public void onEnable() {
        PluginConfig config = new PluginConfig(this);
        config.loadDefaults();

        Lang lang = new Lang(this);
        lang.load();

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

        // tracking tick
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (var p : getServer().getOnlinePlayers()) {
                compassService.tickTracking(p);
            }
        }, 20L, 20L);

        PluginCommand eventCmd = getCommand("event");
        if (eventCmd != null) {
            eventCmd.setExecutor(new EventCommand(lang));
        }
        PluginCommand preciv = getCommand("preciv");
        if (preciv != null) {
            PrecivCommand executor = new PrecivCommand(eventManager, killService, compassService, config, lang);
            preciv.setExecutor(executor);
            preciv.setTabCompleter(executor);
        }

        eventScheduler.start();
        // sync phase metric
        try {
            eventRepository.setMetric("last_enable", Instant.now().toString());
            eventRepository.setMetric("phase", eventManager.phase().name());
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Metric write failed", e);
        }

        getLogger().info("Day of Assassins enabled. Phase=" + eventManager.phase());
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

    public EventManager eventManager() {
        return eventManager;
    }

    public KillService killService() {
        return killService;
    }
}
