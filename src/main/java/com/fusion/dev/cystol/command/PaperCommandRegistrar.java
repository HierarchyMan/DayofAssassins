package com.fusion.dev.cystol.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
 *
 * <h2>ADDING A COMMAND — always do all of this or it will silently not work</h2>
 * <ol>
 *   <li><b>Register here</b> — every player-facing path needs a Brigadier
 *       {@code literal(...)} / {@code argument(...)} node with {@code .executes(...)}.
 *       Paper only dispatches nodes in this tree. A {@code switch} case in
 *       {@link PrecivCommand} alone does <em>nothing</em>.</li>
 *   <li><b>{@link AdminCommands}</b> — add the first token to {@code LEAF_NO_ARGS} or
 *       {@code LEAF_WITH_ARGS} (and {@code HANDLER_ADMIN_ACTIONS}). Suggestions pull
 *       from that list; {@code AdminCommandsSyncTest} fails if you forget.</li>
 *   <li><b>{@link PrecivCommand}</b> — handle the action in the admin (or root) switch.</li>
 *   <li><b>{@link AdminOps}</b> (if needed) — implement the behavior.</li>
 *   <li><b>lang.yml</b> — usage / success / error messages if player-facing.</li>
 * </ol>
 * Zero-arg admin leaves: loop over {@link AdminCommands#LEAF_NO_ARGS}.  
 * Leaves with args ({@code setkills}, {@code phase}, {@code set}, {@code clearkills}):
 * add an explicit {@code admin.then(Commands.literal("…").then(...))} tree below —
 * do not only put them in the zero-arg loop.
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

            // ── admin tree ─────────────────────────────────────────────────────
            // NEW admin subcommand checklist (see class javadoc):
            //   1) AdminCommands.LEAF_NO_ARGS or LEAF_WITH_ARGS (+ HANDLER_ADMIN_ACTIONS)
            //   2) Node below (zero-arg loop OR explicit .then tree with args)
            //   3) PrecivCommand case → AdminOps method
            // Handler-only / suggestions-only = broken on Paper.
            LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                    .requires(src -> src.getSender().hasPermission("preciv.admin") || src.getSender().isOp())
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(), new String[]{"admin"}));

            // Zero-arg leaves from AdminCommands.LEAF_NO_ARGS (single source of truth)
            for (String sub : AdminCommands.LEAF_NO_ARGS) {
                String s = sub;
                admin.then(Commands.literal(s)
                        .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                                new String[]{"admin", s})));
            }

            // --- LEAF_WITH_ARGS: each needs its own argument tree (not the loop above) ---

            admin.then(Commands.literal("clearkills")
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                            new String[]{"admin", "clearkills"}))
                    .then(Commands.literal("confirm")
                            .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                                    new String[]{"admin", "clearkills", "confirm"}))));

            // setkills <player> <amount> — Brigadier node required or Paper never dispatches
            admin.then(Commands.literal("setkills")
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                            new String[]{"admin", "setkills"}))
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestOnlinePlayers(builder))
                            .executes(ctx -> {
                                String player = StringArgumentType.getString(ctx, "player");
                                return run(precivCommand, ctx.getSource().getSender(),
                                        new String[]{"admin", "setkills", player});
                            })
                            .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                    .suggests((ctx, builder) -> {
                                        for (String n : List.of("0", "1", "5", "10", "25", "50", "100")) {
                                            if (n.startsWith(builder.getRemaining())) {
                                                builder.suggest(n);
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        String player = StringArgumentType.getString(ctx, "player");
                                        int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                        return run(precivCommand, ctx.getSource().getSender(),
                                                new String[]{"admin", "setkills", player, String.valueOf(amount)});
                                    }))));

            LiteralArgumentBuilder<CommandSourceStack> phase = Commands.literal("phase")
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                            new String[]{"admin", "phase"}));
            for (String p : AdminCommands.PHASES) {
                String name = p;
                phase.then(Commands.literal(name)
                        .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                                new String[]{"admin", "phase", name})));
            }
            admin.then(phase);

            LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set")
                    .executes(ctx -> run(precivCommand, ctx.getSource().getSender(),
                            new String[]{"admin", "set"}));

            for (String target : AdminCommands.SET_TARGETS) {
                // Times use greedy arg trees below; pos/center are zero-arg player location
                if (target.equals("starttime") || target.equals("endtime") || target.equals("ffatime")) {
                    continue;
                }
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

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(SuggestionsBuilder builder) {
        String rem = builder.getRemaining() == null ? "" : builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = p.getName();
            if (name != null && (rem.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(rem))) {
                builder.suggest(name);
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
