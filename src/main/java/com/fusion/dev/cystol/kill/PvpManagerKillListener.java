package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.fx.EffectService;
import me.chancesd.sdutils.library.utils.Log;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Credits kills using PvPManager when available (combat enemy / damager),
 * without modifying death/kill messages.
 * Falls back to {@link PlayerDeathEvent#getEntity()#getKiller()} if API probe fails.
 */
public final class PvpManagerKillListener implements Listener {

    private final EventManager eventManager;
    private final KillService killService;
    private final EffectService effects;
    private final Logger logger;
    private Object pvpManager;
    private Method getPlayerMethod;
    private Method getEnemyMethod;
    private boolean pvpManagerReady;

    public PvpManagerKillListener(
            EventManager eventManager,
            KillService killService,
            EffectService effects,
            Logger logger
    ) {
        this.eventManager = eventManager;
        this.killService = killService;
        this.effects = effects;
        this.logger = logger;
        hookPvpManager();
    }

    private void hookPvpManager() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PvPManager");
        if (plugin == null || !plugin.isEnabled()) {
            logger.warning("PvPManager not found at runtime; using Bukkit killer fallback.");
            return;
        }
        try {
            // me.chancesd.pvpmanager.PvPManager.getInstance() or JavaPlugin cast
            Class<?> pmClass = Class.forName("me.chancesd.pvpmanager.PvPManager");
            Method getInstance = null;
            try {
                getInstance = pmClass.getMethod("getInstance");
            } catch (NoSuchMethodException ignored) {
            }
            Object pm = getInstance != null ? getInstance.invoke(null) : plugin;
            // PlayerHandler
            Method getPlayerHandler = null;
            for (String name : new String[]{"getPlayerHandler", "getPlayerManager"}) {
                try {
                    getPlayerHandler = pm.getClass().getMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (getPlayerHandler == null) {
                // try PvPManager API facade
                try {
                    Class<?> api = Class.forName("me.chancesd.pvpmanager.api.PvPManagerAPI");
                    // static access may vary by version
                    logger.info("PvPManager present; using death killer with combat metadata fallback.");
                } catch (ClassNotFoundException ignored) {
                }
                this.pvpManager = pm;
                this.pvpManagerReady = true;
                return;
            }
            Object handler = getPlayerHandler.invoke(pm);
            this.getPlayerMethod = findMethod(handler.getClass(), "get", Player.class);
            if (getPlayerMethod == null) {
                getPlayerMethod = findMethod(handler.getClass(), "getPlayer", Player.class);
            }
            this.pvpManager = handler;
            this.pvpManagerReady = true;
            logger.info("Hooked PvPManager player handler for kill credit.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "PvPManager hook failed; using Bukkit killer fallback", e);
            pvpManagerReady = false;
        }
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
        if (!eventManager.killsCountAt(Instant.now())) {
            return;
        }
        Player victim = event.getEntity();
        Player killer = resolveKiller(victim);
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        killService.creditKill(killer.getUniqueId(), killer.getName());
        effects.play(killer, EffectService.EffectKey.KILL_CREDITED, victim.getLocation());
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
