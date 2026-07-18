package com.fusion.dev.cystol.teleport;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft-bind to BetterRTP: reflective call to {@code HelperRTP.tp(...)} with FORCED + ignore delay/cooldown.
 * No compile-time dependency on BetterRTP.
 */
public final class BetterRtpBridge implements Listener {

    public enum Backend {
        BETTER_RTP,
        UNAVAILABLE
    }

    @FunctionalInterface
    public interface RtpCaller {
        /**
         * @return true if the request was handed off to BetterRTP (not a guarantee of successful TP)
         */
        boolean rtp(Player player, World world, CommandSender sendi);
    }

    private final Logger logger;
    private volatile Backend backend = Backend.UNAVAILABLE;
    private volatile RtpCaller caller = (p, w, s) -> false;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public BetterRtpBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        rebind();
    }

    /** Test constructor. */
    public BetterRtpBridge(Logger logger, Backend backend, RtpCaller caller) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    public void register(JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        rebind();
    }

    public Backend backend() {
        return backend;
    }

    public boolean isAvailable() {
        return backend == Backend.BETTER_RTP;
    }

    public String backendLabel() {
        return switch (backend) {
            case BETTER_RTP -> "betterrtp";
            case UNAVAILABLE -> "unavailable";
        };
    }

    /**
     * Request a forced RTP for {@code player} into {@code world} (or null → BetterRTP picks from player).
     *
     * @return false if bridge unavailable or invocation failed
     */
    public boolean requestRtp(Player player, World world) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        try {
            return caller.rtp(player, world, Bukkit.getConsoleSender());
        } catch (RuntimeException e) {
            if (failureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "BetterRTP request failed; further failures are rate-limited in logs", e);
            }
            return false;
        }
    }

    public void rebind() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("BetterRTP");
        if (plugin != null && plugin.isEnabled()) {
            RtpCaller bound = tryBind();
            if (bound != null) {
                this.backend = Backend.BETTER_RTP;
                this.caller = bound;
                failureLogged.set(false);
                logger.info("RTP backend: BetterRTP");
                return;
            }
        }
        this.backend = Backend.UNAVAILABLE;
        this.caller = (p, w, s) -> false;
        logger.info("RTP backend: unavailable (BetterRTP not present or API bind failed)");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if ("BetterRTP".equalsIgnoreCase(event.getPlugin().getName())) {
            rebind();
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if ("BetterRTP".equalsIgnoreCase(event.getPlugin().getName())) {
            rebind();
        }
    }

    /**
     * Reflect: {@code HelperRTP.tp(Player, CommandSender, World, List, RTP_TYPE, boolean ignoreCooldown, boolean ignoreDelay)}.
     */
    private RtpCaller tryBind() {
        try {
            ClassLoader cl = Bukkit.getPluginManager().getPlugin("BetterRTP").getClass().getClassLoader();
            Class<?> helper = Class.forName(
                    "me.SuperRonanCraft.BetterRTP.references.helpers.HelperRTP", false, cl);
            Class<?> rtpTypeClass = Class.forName(
                    "me.SuperRonanCraft.BetterRTP.player.rtp.RTP_TYPE", false, cl);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object forced = Enum.valueOf((Class) rtpTypeClass, "FORCED");

            Method tp = helper.getMethod(
                    "tp",
                    Player.class,
                    CommandSender.class,
                    World.class,
                    List.class,
                    rtpTypeClass,
                    boolean.class,
                    boolean.class
            );

            Object forcedFinal = forced;
            Method tpFinal = tp;
            return (player, world, sendi) -> {
                try {
                    // ignoreCooldown=true, ignoreDelay=true — event dump must be immediate
                    tpFinal.invoke(null, player, sendi, world, null, forcedFinal, true, true);
                    return true;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("HelperRTP.tp reflection failed", e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not bind BetterRTP HelperRTP.tp API", e);
            return null;
        }
    }
}
