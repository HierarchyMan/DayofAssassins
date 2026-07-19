package com.fusion.dev.cystol.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft-bind to EssentialsX homes. Reflective — no compile-time Essentials dependency.
 * Prefer home named {@code home}, else the first listed home.
 */
public final class EssentialsHomeBridge implements Listener {

    public enum Backend {
        ESSENTIALS,
        UNAVAILABLE
    }

    @FunctionalInterface
    public interface HomeResolver {
        /** @return home location, or null if none / unavailable */
        Location findHome(Player player);
    }

    private final Logger logger;
    private volatile Backend backend = Backend.UNAVAILABLE;
    private volatile HomeResolver resolver = p -> null;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public EssentialsHomeBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        rebind();
    }

    /** Test constructor. */
    public EssentialsHomeBridge(Logger logger, Backend backend, HomeResolver resolver) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public void register(JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        rebind();
    }

    public Backend backend() {
        return backend;
    }

    public boolean isAvailable() {
        return backend == Backend.ESSENTIALS;
    }

    /**
     * @return player home if Essentials is bound and a home exists; else null
     */
    public Location findHome(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        try {
            Location loc = resolver.findHome(player);
            if (loc == null || loc.getWorld() == null) {
                return null;
            }
            return loc.clone();
        } catch (RuntimeException e) {
            if (failureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Essentials home resolve failed; further failures rate-limited", e);
            }
            return null;
        }
    }

    public void rebind() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
        if (plugin != null && plugin.isEnabled()) {
            HomeResolver bound = tryBind(plugin);
            if (bound != null) {
                this.backend = Backend.ESSENTIALS;
                this.resolver = bound;
                failureLogged.set(false);
                logger.info("Home backend: Essentials");
                return;
            }
        }
        this.backend = Backend.UNAVAILABLE;
        this.resolver = p -> null;
        logger.info("Home backend: unavailable (Essentials not present or API bind failed)");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if ("Essentials".equalsIgnoreCase(event.getPlugin().getName())) {
            rebind();
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if ("Essentials".equalsIgnoreCase(event.getPlugin().getName())) {
            rebind();
        }
    }

    private HomeResolver tryBind(Plugin essentialsPlugin) {
        try {
            Method getUser = findGetUser(essentialsPlugin.getClass());
            if (getUser == null) {
                logger.warning("Essentials getUser(Player) not found");
                return null;
            }
            Object essentials = essentialsPlugin;
            Method getUserFinal = getUser;
            return player -> {
                try {
                    Object user = getUserFinal.invoke(essentials, player);
                    if (user == null) {
                        return null;
                    }
                    return resolveHomeFromUser(user);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Essentials getUser failed", e);
                }
            };
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not bind Essentials home API", e);
            return null;
        }
    }

    private static Method findGetUser(Class<?> type) {
        try {
            return type.getMethod("getUser", Player.class);
        } catch (NoSuchMethodException e) {
            // some forks
            try {
                return type.getMethod("getUser", org.bukkit.OfflinePlayer.class);
            } catch (NoSuchMethodException e2) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Location resolveHomeFromUser(Object user) throws ReflectiveOperationException {
        // Prefer named "home"
        Location named = tryGetHome(user, "home");
        if (named != null) {
            return named;
        }
        // First home name from getHomes()
        Method getHomes = findNoArg(user.getClass(), "getHomes");
        if (getHomes != null) {
            Object homes = getHomes.invoke(user);
            if (homes instanceof List<?> list && !list.isEmpty()) {
                Object first = list.getFirst();
                if (first instanceof String name) {
                    Location loc = tryGetHome(user, name);
                    if (loc != null) {
                        return loc;
                    }
                }
            } else if (homes instanceof Collection<?> col && !col.isEmpty()) {
                Object first = col.iterator().next();
                if (first instanceof String name) {
                    Location loc = tryGetHome(user, name);
                    if (loc != null) {
                        return loc;
                    }
                }
            }
        }
        return null;
    }

    private static Location tryGetHome(Object user, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Method getHome = user.getClass().getMethod("getHome", String.class);
            Object loc = getHome.invoke(user, name);
            if (loc instanceof Location l && l.getWorld() != null) {
                return l;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static Method findNoArg(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
