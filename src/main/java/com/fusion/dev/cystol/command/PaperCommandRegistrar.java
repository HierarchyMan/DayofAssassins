package com.fusion.dev.cystol.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Paper Brigadier registration. Uses a <strong>literal tree</strong> so tab-complete works.
 * A single greedy {@code args} string does <em>not</em> suggest root subcommands after
 * {@code /preciv} — the client only asks the active node for suggestions.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperCommandRegistrar {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private PaperCommandRegistrar() {
    }

    public static void register(
            JavaPlugin plugin,
            Supplier<EventCommand> eventCommand,
            Supplier<PrecivCommand> precivCommand
    ) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            LiteralCommandNode<CommandSourceStack> eventNode = Commands.literal("event")
                    .requires(src -> src.getSender().hasPermission("preciv.event"))
                    .executes(ctx -> {
                        EventCommand cmd = eventCommand.get();
                        if (cmd != null) {
                            cmd.execute(ctx.getSource().getSender());
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .build();
            commands.register(eventNode, "Day of Assassins event description", List.of());

            LiteralArgumentBuilder<CommandSourceStack> preciv = Commands.literal("preciv")
                    .requires(src -> {
                        CommandSender s = src.getSender();
                        return s.hasPermission("preciv.use")
                                || s.hasPermission("preciv.compass")
                                || s.hasPermission("preciv.killtop")
                                || s.hasPermission("preciv.admin")
                                || s.isOp();
                    })
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(), new String[0]));

            // Root literals — tab after /preciv works
            preciv.then(Commands.literal("compass")
                    .requires(src -> src.getSender().hasPermission("preciv.compass") || src.getSender().isOp())
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(), new String[]{"compass"})));

            preciv.then(Commands.literal("killtop")
                    .requires(src -> src.getSender().hasPermission("preciv.killtop") || src.getSender().isOp())
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(), new String[]{"killtop"})));

            preciv.then(Commands.literal("gui")
                    .requires(src -> src.getSender().hasPermission("preciv.admin") || src.getSender().isOp())
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(), new String[]{"gui"})));

            preciv.then(Commands.literal("nobypass")
                    .requires(src -> src.getSender().hasPermission("preciv.admin") || src.getSender().isOp())
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(), new String[]{"nobypass"})));

            // admin tree
            LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                    .requires(src -> src.getSender().hasPermission("preciv.admin") || src.getSender().isOp())
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(), new String[]{"admin"}));

            for (String sub : List.of(
                    "status", "startnow", "ffanow", "endnow", "pause", "unpause",
                    "forcetp", "forcespawnrtp", "forceceremony", "resetflags", "eligible",
                    "reload", "wand", "spawnwand"
            )) {
                String s = sub;
                admin.then(Commands.literal(s)
                        .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                                new String[]{"admin", s})));
            }

            admin.then(Commands.literal("clearkills")
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                            new String[]{"admin", "clearkills"}))
                    .then(Commands.literal("confirm")
                            .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                                    new String[]{"admin", "clearkills", "confirm"}))));

            LiteralArgumentBuilder<CommandSourceStack> phase = Commands.literal("phase")
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                            new String[]{"admin", "phase"}));
            for (String p : List.of("idle", "paused", "countdown", "hunt", "ffa", "ended")) {
                String name = p;
                phase.then(Commands.literal(name)
                        .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                                new String[]{"admin", "phase", name})));
            }
            admin.then(phase);

            LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set")
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                            new String[]{"admin", "set"}));

            for (String target : List.of(
                    "centerspawn", "pos1", "pos2", "spawnpos1", "spawnpos2"
            )) {
                String t = target;
                set.then(Commands.literal(t)
                        .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                                new String[]{"admin", "set", t})));
            }

            // Times: greedy so "yyyy/MM/dd HH:mm:ss" and "-r 1d2h" work
            set.then(timeArg("starttime", precivCommand));
            set.then(timeArg("endtime", precivCommand));
            set.then(timeArg("ffatime", precivCommand));

            admin.then(set);
            preciv.then(admin);

            commands.register(preciv.build(), "Day of Assassins commands", List.of("doa"));
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> timeArg(
            String name,
            Supplier<PrecivCommand> precivCommand
    ) {
        boolean ffa = name.equals("ffatime");
        return Commands.literal(name)
                .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                        new String[]{"admin", "set", name}))
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> suggestTimeValue(builder, ffa))
                        .executes(ctx -> {
                            String value = StringArgumentType.getString(ctx, "value");
                            String[] parts = value.trim().split("\\s+");
                            String[] args = new String[3 + parts.length];
                            args[0] = "admin";
                            args[1] = "set";
                            args[2] = name;
                            System.arraycopy(parts, 0, args, 3, parts.length);
                            return run(precivCommand, ctx.getSource().getSender(), args);
                        }));
    }

    private static CompletableFuture<Suggestions> suggestTimeValue(
            SuggestionsBuilder builder,
            boolean allowClear
    ) {
        String rem = builder.getRemaining() == null ? "" : builder.getRemaining();
        String lower = rem.toLowerCase(Locale.ROOT).trim();
        Instant now = Instant.now();
        LocalDateTime utc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String date = DATE.format(utc);
        String time = TIME.format(utc);

        // Suggest next token(s) only — Brigadier replaces from this argument's start
        if (lower.isEmpty() || lower.equals("-") || lower.startsWith("-r") && !lower.contains(" ")) {
            if (allowClear && "clear".startsWith(lower.isEmpty() ? "" : lower)) {
                if (lower.isEmpty() || "clear".startsWith(lower)) {
                    builder.suggest("clear");
                }
            }
            if (lower.isEmpty() || "-r".startsWith(lower) || lower.equals("-r")) {
                builder.suggest("-r");
                builder.suggest("-r 1h");
                builder.suggest("-r 30m");
                builder.suggest("-r 1d");
                builder.suggest("-r 1d2h");
            }
            if (lower.isEmpty() || date.startsWith(lower) || lower.startsWith(date.substring(0, Math.min(lower.length(), date.length())))) {
                builder.suggest(date);
            }
        } else if (lower.startsWith("-r ")) {
            String after = rem.substring(rem.toLowerCase(Locale.ROOT).indexOf("-r") + 2).trim();
            // Re-suggest full remaining for duration tokens under -r
            for (String d : List.of("1h", "30m", "1d", "1d2h", "2h30m", "5m")) {
                if (after.isEmpty() || d.startsWith(after.toLowerCase(Locale.ROOT))) {
                    builder.suggest("-r " + d);
                }
            }
        } else if (!lower.contains(" ") && lower.contains("/")) {
            // date typed, suggest times
            builder.suggest(rem.trim() + " " + time);
            builder.suggest(rem.trim() + " 00:00:00");
            builder.suggest(rem.trim() + " 12:00:00");
            builder.suggest(rem.trim() + " 18:00:00");
        } else if (lower.startsWith(date.toLowerCase(Locale.ROOT)) || Character.isDigit(lower.charAt(0))) {
            // partial date
            if (date.startsWith(lower.replace(' ', 'X').isEmpty() ? lower : lower.split("\\s+")[0])
                    || lower.split("\\s+")[0].length() < 10) {
                builder.suggest(date);
            }
        }
        return builder.buildFuture();
    }

    private static int run(Supplier<PrecivCommand> supplier, CommandSender sender, String[] args) {
        PrecivCommand cmd = supplier.get();
        if (cmd != null) {
            cmd.execute(sender, args);
        }
        return Command.SINGLE_SUCCESS;
    }
}
