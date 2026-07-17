package com.fusion.dev.cystol.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vanish detection: Essentials/EssentialsX when present, else metadata {@code vanished}.
 * <p>
 * When Essentials is active, <em>only</em> its API is used (metadata is not OR'd).
 * When Essentials is absent or fails to bind, the legacy metadata check is kept unchanged.
 */
public final class VanishService implements Listener {

    public enum Backend {
        ESSENTIALS,
        METADATA
    }

    /** Pure strategy for tests — no Bukkit required. */
    @FunctionalInterface
    public interface VanishCheck {
        boolean isVanished(Player player);
    }

    private final Logger logger;
    private volatile Backend backend = Backend.METADATA;
    private volatile VanishCheck check = VanishService::metadataVanished;
    private final AtomicBoolean essentialsFailureLogged = new AtomicBoolean(false);

    public VanishService(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        rebind();
    }

    /** Test constructor: fixed strategy and backend label. */
    public VanishService(Logger logger, Backend backend, VanishCheck check) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.check = Objects.requireNonNull(check, "check");
    }

    public void register(JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        rebind();
    }

    public Backend backend() {
        return backend;
    }

    public String backendLabel() {
        return switch (backend) {
            case ESSENTIALS -> "essentials";
            case METADATA -> "metadata";
        };
    }

    public boolean isVanished(Player player) {
        if (player == null) {
            return false;
        }
        try {
            return check.isVanished(player);
        } catch (RuntimeException e) {
            if (backend == Backend.ESSENTIALS && essentialsFailureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Essentials vanish check failed; falling back to metadata for this call", e);
            }
            // One-shot soft fallthrough for this call only when Essentials misbehaves mid-event.
            if (backend == Backend.ESSENTIALS) {
                return metadataVanished(player);
            }
            throw e;
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (isEssentialsName(event.getPlugin().getName())) {
            rebind();
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (isEssentialsName(event.getPlugin().getName())) {
            rebind();
        }
    }

    public void rebind() {
        Plugin ess = findEssentialsPlugin();
        if (ess != null && ess.isEnabled()) {
            VanishCheck essentials = tryBindEssentials(ess);
            if (essentials != null) {
                this.backend = Backend.ESSENTIALS;
                this.check = essentials;
                essentialsFailureLogged.set(false);
                logger.info("Vanish backend: Essentials");
                return;
            }
        }
        this.backend = Backend.METADATA;
        this.check = VanishService::metadataVanished;
        logger.info("Vanish backend: metadata (vanished)");
    }

    private static boolean isEssentialsName(String name) {
        return "Essentials".equalsIgnoreCase(name) || "EssentialsX".equalsIgnoreCase(name);
    }

    private static Plugin findEssentialsPlugin() {
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess != null) {
            return ess;
        }
        return Bukkit.getPluginManager().getPlugin("EssentialsX");
    }

    /**
     * Reflective bind so Essentials is never a compile/hard dependency.
     * Expects {@code getUser(Player)} → object with {@code isVanished()}.
     */
    private VanishCheck tryBindEssentials(Plugin essPlugin) {
        try {
            Method getUser = essPlugin.getClass().getMethod("getUser", Player.class);
            // Probe once
            Object probeUser = null;
            for (Player online : Bukkit.getOnlinePlayers()) {
                probeUser = getUser.invoke(essPlugin, online);
                if (probeUser != null) {
                    break;
                }
            }
            Method isVanishedMethod;
            if (probeUser != null) {
                isVanishedMethod = probeUser.getClass().getMethod("isVanished");
            } else {
                // No one online: resolve from IUser / User type if available
                Class<?> userClass = Class.forName("com.earth2me.essentials.User", false, essPlugin.getClass().getClassLoader());
                isVanishedMethod = userClass.getMethod("isVanished");
            }
            Method getUserFinal = getUser;
            Method isVanishedFinal = isVanishedMethod;
            return player -> {
                try {
                    Object user = getUserFinal.invoke(essPlugin, player);
                    if (user == null) {
                        return false;
                    }
                    Object result = isVanishedFinal.invoke(user);
                    return result instanceof Boolean b && b;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Essentials vanish reflection failed", e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not bind Essentials vanish API; using metadata fallback", e);
            return null;
        }
    }

    /** Legacy convention used by many vanish plugins / staff tools. */
    public static boolean metadataVanished(Player player) {
        if (player == null || !player.hasMetadata("vanished") || player.getMetadata("vanished").isEmpty()) {
            return false;
        }
        return player.getMetadata("vanished").getFirst().asBoolean();
    }

    /** Test helper: build a service that uses a custom predicate under METADATA label. */
    public static VanishService forTests(Predicate<Player> predicate) {
        return new VanishService(
                Logger.getLogger("VanishServiceTest"),
                Backend.METADATA,
                predicate::test
        );
    }
}
