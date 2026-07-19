package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.arena.CuboidBounds;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.fx.EffectService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Credits kills using PvPManager when available (combat enemy / damager),
 * without modifying death/kill messages.
 * Falls back to {@link PlayerDeathEvent#getEntity()#getKiller()} if API probe fails.
 *
 * <p>FFA: only deaths where both players are inside the arena cuboid count.
 */
public final class PvpManagerKillListener implements Listener {

    private final EventManager eventManager;
    private final KillService killService;
    private final PluginConfig config;
    private final EffectService effects;
    private final Lang lang;
    private final Logger logger;
    private Object pvpManager;
    private Method getPlayerMethod;
    private Method getEnemyMethod;
    private boolean pvpManagerReady;

    public PvpManagerKillListener(
            EventManager eventManager,
            KillService killService,
            PluginConfig config,
            EffectService effects,
            Lang lang,
            Logger logger
    ) {
        this.eventManager = eventManager;
        this.killService = killService;
        this.config = config;
        this.effects = effects;
        this.lang = lang;
        this.logger = logger;
        hookPvpManager();
    }

    /**
     * Main plugin class names across PvPManager 3.x packaging.
     * 3.19.x ships {@code me.NoChance.PvPManager.PvPManager}; some forks use chancesd package.
     */
    private static final String[] PVP_MANAGER_MAIN_CLASSES = {
            "me.NoChance.PvPManager.PvPManager",
            "me.chancesd.pvpmanager.PvPManager"
    };

    private void hookPvpManager() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PvPManager");
        if (plugin == null || !plugin.isEnabled()) {
            logger.warning("PvPManager not found at runtime; using Bukkit killer fallback.");
            return;
        }
        try {
            Object pm = resolvePvpManagerInstance(plugin);
            if (pm == null) {
                logger.warning("PvPManager plugin present but main class not resolved; Bukkit killer fallback.");
                return;
            }
            // PlayerHandler / PlayerManager
            Method getPlayerHandler = null;
            for (String name : new String[]{"getPlayerHandler", "getPlayerManager"}) {
                try {
                    getPlayerHandler = pm.getClass().getMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (getPlayerHandler == null) {
                // Keep instance for any future probes; killer still falls back to Bukkit.
                this.pvpManager = pm;
                this.pvpManagerReady = true;
                logger.info("PvPManager hooked (" + pm.getClass().getName()
                        + ") without player handler — Bukkit killer when enemy resolve fails.");
                return;
            }
            Object handler = getPlayerHandler.invoke(pm);
            this.getPlayerMethod = findMethod(handler.getClass(), "get", Player.class);
            if (getPlayerMethod == null) {
                getPlayerMethod = findMethod(handler.getClass(), "getPlayer", Player.class);
            }
            this.pvpManager = handler;
            this.pvpManagerReady = true;
            logger.info("Hooked PvPManager player handler for kill credit ("
                    + pm.getClass().getName() + ").");
        } catch (Exception e) {
            logger.log(Level.WARNING, "PvPManager hook failed; using Bukkit killer fallback", e);
            pvpManagerReady = false;
        }
    }

    /**
     * Prefer {@code getInstance()} on known main classes, then the Bukkit plugin instance itself.
     */
    private static Object resolvePvpManagerInstance(Plugin plugin) {
        ClassLoader cl = plugin.getClass().getClassLoader();
        for (String className : PVP_MANAGER_MAIN_CLASSES) {
            try {
                Class<?> pmClass = Class.forName(className, true, cl);
                try {
                    Method getInstance = pmClass.getMethod("getInstance");
                    Object inst = getInstance.invoke(null);
                    if (inst != null) {
                        return inst;
                    }
                } catch (NoSuchMethodException ignored) {
                    // fall through to assignable plugin instance
                }
                if (pmClass.isInstance(plugin)) {
                    return plugin;
                }
            } catch (ClassNotFoundException ignored) {
                // try next packaging
            } catch (ReflectiveOperationException ignored) {
                // try next packaging
            }
        }
        // Last resort: the JavaPlugin instance (works if methods live on the plugin class).
        return plugin;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        // Do not modify event death message
        Instant now = Instant.now();
        if (!eventManager.killsCountAt(now)) {
            return;
        }
        Player victim = event.getEntity();
        Player killer = resolveKiller(victim);
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        EventPhase phase = eventManager.phase();
        if (!KillCreditRules.locationAllowsCredit(
                phase,
                isInArena(killer.getLocation()),
                isInArena(victim.getLocation())
        )) {
            return;
        }
        // Anti-farm: same killer→victim limited credits per window (memory only)
        if (config.killAntiFarmEnabled()) {
            int max = config.killAntiFarmMaxCreditsPerVictim();
            long windowMs = config.killAntiFarmWindowSeconds() * 1000L;
            boolean allowed = killService.farmGuard().tryRegisterCredit(
                    killer.getUniqueId(), victim.getUniqueId(), max, windowMs
            );
            if (!allowed) {
                long remainMs = killService.farmGuard().remainingBlockMs(
                        killer.getUniqueId(), victim.getUniqueId(), max, windowMs
                );
                long remainSec = Math.max(1L, (remainMs + 999L) / 1000L);
                killer.sendMessage(lang.msg("kill.anti-farm", Map.of(
                        "victim", victim.getName(),
                        "seconds", String.valueOf(remainSec)
                )));
                return;
            }
        }
        killService.creditKill(killer.getUniqueId(), killer.getName());
        effects.play(killer, EffectService.EffectKey.KILL_CREDITED, victim.getLocation());

        Map<String, String> placeholders = Map.of(
                "killer", killer.getName(),
                "victim", victim.getName(),
                "kills", String.valueOf(killService.getKills(killer.getUniqueId()))
        );
        effects.showTitle(
                killer,
                EffectService.EffectKey.KILL_CREDITED,
                lang.msg("kill.credited-title", placeholders),
                lang.msg("kill.credited-subtitle", placeholders)
        );
        effects.playToAllExcept(killer, EffectService.EffectKey.KILL_GLOBAL);
    }

    private boolean isInArena(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (!loc.getWorld().getName().equals(config.arenaWorld())) {
            return false;
        }
        CuboidBounds cuboid = config.arenaCuboid();
        return cuboid.contains(loc.getX(), loc.getY(), loc.getZ());
    }

    private Player resolveKiller(Player victim) {
        Player fromApi = resolveFromPvpManager(victim);
        if (fromApi != null) {
            return fromApi;
        }
        return victim.getKiller();
    }

    private Player resolveFromPvpManager(Player victim) {
        if (!pvpManagerReady || pvpManager == null) {
            return null;
        }
        try {
            Object pvpPlayer = null;
            if (getPlayerMethod != null) {
                pvpPlayer = getPlayerMethod.invoke(pvpManager, victim);
            }
            if (pvpPlayer == null) {
                return null;
            }
            // common method names across versions
            for (String methodName : new String[]{"getEnemy", "getLastAttacker", "getDamager", "getCombatEnemy"}) {
                try {
                    Method m = pvpPlayer.getClass().getMethod(methodName);
                    Object enemy = m.invoke(pvpPlayer);
                    if (enemy instanceof Player p) {
                        return p;
                    }
                    if (enemy != null) {
                        try {
                            Method getPlayer = enemy.getClass().getMethod("getPlayer");
                            Object p = getPlayer.invoke(enemy);
                            if (p instanceof Player pl) {
                                return pl;
                            }
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
            // inCombat check + getKiller still
            return null;
        } catch (Exception e) {
            logger.log(Level.FINE, "PvPManager resolve failed", e);
            return null;
        }
    }
}
