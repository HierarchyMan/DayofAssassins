package com.fusion.dev.cystol.command;

import com.fusion.dev.cystol.compass.CompassService;
import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.kill.DenseRanking;
import com.fusion.dev.cystol.kill.KillService;
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

    public PrecivCommand(
            EventManager eventManager,
            KillService killService,
            CompassService compassService,
            PluginConfig config,
            Lang lang
    ) {
        this.eventManager = eventManager;
        this.killService = killService;
        this.compassService = compassService;
        this.config = config;
        this.lang = lang;
    }

    /** Paper Brigadier entry (no Bukkit {@link Command} instance). */
    public void execute(CommandSender sender, String[] args) {
        onCommand(sender, null, "preciv", args == null ? new String[0] : args);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /preciv <compass|killtop|admin>"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "compass" -> handleCompass(sender);
            case "killtop" -> handleKilltop(sender);
            case "admin" -> handleAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> sender.sendMessage(Component.text("Usage: /preciv <compass|killtop|admin>"));
        }
        return true;
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
            sender.sendMessage(Component.text("/preciv admin set <starttime|endtime|ffatime|centerspawn|pos1|pos2> | wand"));
            return;
        }
        if (args[0].equalsIgnoreCase("wand")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.msg("admin.not-player"));
                return;
            }
            player.getInventory().addItem(compassService.createWand());
            player.sendMessage(lang.msg("admin.wand-given"));
            return;
        }
        if (!args[0].equalsIgnoreCase("set") || args.length < 2) {
            sender.sendMessage(Component.text("/preciv admin set <starttime|endtime|ffatime|centerspawn|pos1|pos2> | wand"));
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
            default -> sender.sendMessage(Component.text("Unknown admin set target."));
        }
    }

    private void setTime(CommandSender sender, String[] args, boolean start) {
        // args: set starttime yyyy/MM/dd HH:mm:ss  -> need join from index 2
        if (args.length < 4) {
            sender.sendMessage(lang.msg("admin.invalid-time"));
            return;
        }
        String raw = args[2] + " " + args[3];
        Optional<Instant> parsed = TimeUtil.parseUtc(raw);
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
    }

    private void setFfa(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            eventManager.setFfaOverride(null);
            sender.sendMessage(lang.msg("admin.ffa-cleared"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(lang.msg("admin.invalid-time"));
            return;
        }
        String raw = args[2] + " " + args[3];
        Optional<Instant> parsed = TimeUtil.parseUtc(raw);
        if (parsed.isEmpty()) {
            sender.sendMessage(lang.msg("admin.invalid-time"));
            return;
        }
        eventManager.setFfaOverride(parsed.get());
        sender.sendMessage(lang.msg("admin.ffa-set", Map.of("time", TimeUtil.formatUtc(parsed.get()))));
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
