package com.fusion.dev.cystol.command;

import com.fusion.dev.cystol.compass.CompassService;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.host.HostGui;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.teleport.NoBypassService;
import com.fusion.dev.cystol.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class PrecivCommand implements CommandExecutor, TabCompleter {

    private final EventManager eventManager;
    private final KillService killService;
    private final CompassService compassService;
    private final PluginConfig config;
    private final Lang lang;
    private final AdminOps adminOps;
    private final HostGui hostGui;
    private final NoBypassService noBypass;

    public PrecivCommand(
            EventManager eventManager,
            KillService killService,
            CompassService compassService,
            PluginConfig config,
            Lang lang,
            AdminOps adminOps,
            HostGui hostGui,
            NoBypassService noBypass
    ) {
        this.eventManager = eventManager;
        this.killService = killService;
        this.compassService = compassService;
        this.config = config;
        this.lang = lang;
        this.adminOps = adminOps;
        this.hostGui = hostGui;
        this.noBypass = noBypass;
    }

    /** Paper Brigadier entry (no Bukkit {@link Command} instance). */
    public void execute(CommandSender sender, String[] args) {
        onCommand(sender, null, "preciv", args == null ? new String[0] : args);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /preciv <compass|killtop|gui|nobypass|admin>"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "compass" -> handleCompass(sender);
            case "killtop" -> handleKilltop(sender);
            case "gui" -> handleGui(sender);
            case "nobypass" -> handleNobypass(sender);
            case "admin" -> handleAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> sender.sendMessage(Component.text("Usage: /preciv <compass|killtop|gui|nobypass|admin>"));
        }
        return true;
    }

    private void handleNobypass(CommandSender sender) {
        if (!sender.hasPermission("preciv.admin")) {
            sender.sendMessage(lang.msg("messages.no-permission"));
            return;
        }
        if (noBypass == null) {
            sender.sendMessage(Component.text("Nobypass is not available."));
            return;
        }
        boolean nowOn = noBypass.toggle();
        sender.sendMessage(lang.msg(nowOn ? "messages.nobypass-on" : "messages.nobypass-off"));
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.msg("messages.players-only"));
            return;
        }
        if (!player.hasPermission("preciv.admin")) {
            player.sendMessage(lang.msg("messages.no-permission"));
            return;
        }
        if (hostGui == null) {
            player.sendMessage(Component.text("Host GUI is not available."));
            return;
        }
        hostGui.openMain(player);
    }

    private void handleCompass(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.msg("messages.players-only"));
            return;
        }
        if (!player.hasPermission("preciv.compass")) {
            player.sendMessage(lang.msg("messages.no-permission"));
            return;
        }
        EventPhase phase = eventManager.phase();
        if (phase != EventPhase.HUNT && phase != EventPhase.FFA) {
            player.sendMessage(lang.msg("compass.not-during-event"));
            return;
        }
        CompassService.GiveResult result = compassService.tryGive(player);
        switch (result) {
            case GIVEN -> player.sendMessage(lang.msg("compass.received"));
            case ALREADY_HAS -> player.sendMessage(lang.msg("compass.already-have"));
            case INVENTORY_FULL -> player.sendActionBar(lang.msg("compass.inv-full-actionbar"));
        }
    }

    private void handleKilltop(CommandSender sender) {
        if (!sender.hasPermission("preciv.killtop")) {
            sender.sendMessage(lang.msg("messages.no-permission"));
            return;
        }
        List<DenseRanking.Entry> ranking = killService.ranking();
        sender.sendMessage(lang.msg("killtop.header"));
        if (ranking.isEmpty()) {
            sender.sendMessage(lang.msg("killtop.empty"));
            return;
        }
        int shown = 0;
        for (DenseRanking.Entry e : ranking) {
            if (shown++ >= 15) {
                break;
            }
            sender.sendMessage(lang.msg("killtop.entry", Map.of(
                    "position", String.valueOf(e.place()),
                    "player", e.name() == null ? "?" : e.name(),
                    "kills", String.valueOf(e.kills())
            )));
        }
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("preciv.admin")) {
            sender.sendMessage(lang.msg("messages.no-permission"));
            return;
        }
        if (args.length == 0) {
            adminOps.sendUsage(sender);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> adminOps.status(sender);
            case "startnow" -> adminOps.startNow(sender);
            case "ffanow" -> adminOps.ffaNow(sender);
            case "endnow" -> adminOps.endNow(sender);
            case "forcetp" -> adminOps.forceTp(sender);
            case "forcespawnrtp" -> adminOps.forceSpawnRtp(sender);
            case "forceceremony" -> adminOps.forceCeremony(sender);
            case "resetflags" -> adminOps.resetFlags(sender);
            case "pause" -> adminOps.pause(sender);
            case "unpause" -> adminOps.unpause(sender);
            case "eligible" -> adminOps.eligible(sender);
            case "clearkills" -> {
                boolean confirm = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
                adminOps.clearKills(sender, confirm);
            }
            case "reload" -> adminOps.reload(sender);
            case "phase" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang.msg("admin.phase-usage"));
                } else {
                    adminOps.setPhase(sender, args[1]);
                }
            }
            case "wand" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.msg("admin.not-player"));
                    return;
                }
                player.getInventory().addItem(compassService.createWand());
                player.sendMessage(lang.msg("admin.wand-given"));
            }
            case "spawnwand" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.msg("admin.not-player"));
                    return;
                }
                player.getInventory().addItem(compassService.createSpawnWand());
                player.sendMessage(lang.msg("admin.spawnwand-given"));
            }
            case "set" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text(
                            "/preciv admin set <starttime|ffatime|endtime|centerspawn|pos1|pos2|spawnpos1|spawnpos2>"
                    ));
                    return;
                }
                String what = args[1].toLowerCase(Locale.ROOT);
                switch (what) {
                    case "starttime" -> setTime(sender, args, true);
                    case "endtime" -> setTime(sender, args, false);
                    case "ffatime" -> setFfa(sender, args);
                    case "centerspawn" -> setCenter(sender);
                    case "pos1" -> setPos(sender, true);
                    case "pos2" -> setPos(sender, false);
                    case "spawnpos1" -> setSpawnPos(sender, true);
                    case "spawnpos2" -> setSpawnPos(sender, false);
                    default -> sender.sendMessage(Component.text("Unknown admin set target."));
                }
            }
            default -> adminOps.sendUsage(sender);
        }
    }

    private void setTime(CommandSender sender, String[] args, boolean start) {
        // args: set starttime <yyyy/MM/dd HH:mm:ss | -r 1d2h5m>
        Optional<Instant> parsed = TimeUtil.parseCommandTimeArgs(args, 2, Instant.now());
        if (parsed.isEmpty()) {
            sender.sendMessage(lang.msg("admin.invalid-time"));
            return;
        }
        if (start) {
            eventManager.setStart(parsed.get());
            sender.sendMessage(lang.msg("admin.start-set", Map.of("time", TimeUtil.formatUtc(parsed.get()))));
        } else {
            eventManager.setEnd(parsed.get());
            sender.sendMessage(lang.msg("admin.end-set", Map.of("time", TimeUtil.formatUtc(parsed.get()))));
        }
        sender.sendMessage(lang.msg("admin.schedule-paused-hint"));
    }

    private void setFfa(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            eventManager.setFfaOverride(null);
            sender.sendMessage(lang.msg("admin.ffa-cleared"));
            sender.sendMessage(lang.msg("admin.schedule-paused-hint"));
            return;
        }
        Optional<Instant> parsed = TimeUtil.parseCommandTimeArgs(args, 2, Instant.now());
        if (parsed.isEmpty()) {
            sender.sendMessage(lang.msg("admin.invalid-time"));
            return;
        }
        eventManager.setFfaOverride(parsed.get());
        sender.sendMessage(lang.msg("admin.ffa-set", Map.of("time", TimeUtil.formatUtc(parsed.get()))));
        sender.sendMessage(lang.msg("admin.schedule-paused-hint"));
    }

    private void setCenter(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.msg("admin.not-player"));
            return;
        }
        Location loc = player.getLocation();
        config.setCenter(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        player.sendMessage(lang.msg("admin.centerspawn-set"));
    }

    private void setPos(CommandSender sender, boolean pos1) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.msg("admin.not-player"));
            return;
        }
        Location loc = player.getLocation();
        config.setArenaWorld(loc.getWorld().getName());
        if (pos1) {
            config.setPos1(loc.getX(), loc.getY(), loc.getZ());
            player.sendMessage(lang.msg("admin.pos1-set", Map.of(
                    "x", String.valueOf(loc.getBlockX()),
                    "y", String.valueOf(loc.getBlockY()),
                    "z", String.valueOf(loc.getBlockZ())
            )));
        } else {
            config.setPos2(loc.getX(), loc.getY(), loc.getZ());
            player.sendMessage(lang.msg("admin.pos2-set", Map.of(
                    "x", String.valueOf(loc.getBlockX()),
                    "y", String.valueOf(loc.getBlockY()),
                    "z", String.valueOf(loc.getBlockZ())
            )));
        }
    }

    private void setSpawnPos(CommandSender sender, boolean pos1) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.msg("admin.not-player"));
            return;
        }
        Location loc = player.getLocation();
        config.setSpawnWorld(loc.getWorld().getName());
        if (pos1) {
            config.setSpawnPos1(loc.getX(), loc.getY(), loc.getZ());
            player.sendMessage(lang.msg("admin.spawnpos1-set", Map.of(
                    "x", String.valueOf(loc.getBlockX()),
                    "y", String.valueOf(loc.getBlockY()),
                    "z", String.valueOf(loc.getBlockZ())
            )));
        } else {
            config.setSpawnPos2(loc.getX(), loc.getY(), loc.getZ());
            player.sendMessage(lang.msg("admin.spawnpos2-set", Map.of(
                    "x", String.valueOf(loc.getBlockX()),
                    "y", String.valueOf(loc.getBlockY()),
                    "z", String.valueOf(loc.getBlockZ())
            )));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Legacy Bukkit path (unused by Paper Brigadier). Rebuild remaining for shared logic.
        String remaining = args == null || args.length == 0
                ? ""
                : String.join(" ", args);
        List<String> full = PrecivSuggestions.complete(sender, remaining);
        // Bukkit TabCompleter expects only the current token, not full remaining
        List<String> tokens = new ArrayList<>(full.size());
        for (String f : full) {
            int sp = f.lastIndexOf(' ');
            tokens.add(sp < 0 ? f : f.substring(sp + 1));
        }
        return tokens;
    }
}
