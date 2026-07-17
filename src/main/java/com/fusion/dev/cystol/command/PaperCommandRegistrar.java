package com.fusion.dev.cystol.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Paper-plugin command registration (Brigadier). Paper plugins do not declare commands in YAML.
 * Register the lifecycle handler in {@code onLoad} so it is bound before the COMMANDS event fires.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperCommandRegistrar {

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

            LiteralCommandNode<CommandSourceStack> precivNode = Commands.literal("preciv")
                    .requires(src -> {
                        CommandSender s = src.getSender();
                        return s.hasPermission("preciv.use")
                                || s.hasPermission("preciv.compass")
                                || s.hasPermission("preciv.killtop")
                                || s.hasPermission("preciv.admin")
                                || s.isOp();
                    })
                    .executes(ctx -> {
                        PrecivCommand cmd = precivCommand.get();
                        if (cmd != null) {
                            cmd.execute(ctx.getSource().getSender(), new String[0]);
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(PaperCommandRegistrar::suggestPreciv)
                            .executes(ctx -> {
                                PrecivCommand cmd = precivCommand.get();
                                if (cmd != null) {
                                    String raw = StringArgumentType.getString(ctx, "args");
                                    cmd.execute(ctx.getSource().getSender(), splitArgs(raw));
                                }
                                return Command.SINGLE_SUCCESS;
                            }))
                    .build();
            commands.register(precivNode, "Day of Assassins commands", List.of("doa"));
        });
    }

    private static String[] splitArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return raw.trim().split("\\s+");
    }

    private static CompletableFuture<Suggestions> suggestPreciv(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        String[] parts = remaining.isBlank() ? new String[0] : remaining.split("\\s+", -1);
        List<String> suggestions = new ArrayList<>();

        if (parts.length <= 1) {
            suggestions.addAll(List.of("compass", "killtop", "admin"));
        } else if (parts[0].equals("admin")) {
            if (parts.length == 2) {
                suggestions.addAll(List.of("set", "wand"));
            } else if (parts.length >= 3 && parts[1].equals("set")) {
                if (parts.length == 3) {
                    suggestions.addAll(List.of(
                            "starttime", "endtime", "ffatime", "centerspawn", "pos1", "pos2"
                    ));
                } else if (parts.length == 4 && parts[2].equals("ffatime")) {
                    suggestions.add("clear");
                }
            }
        }

        String prefix = parts.length == 0 ? "" : parts[parts.length - 1];
        for (String s : suggestions) {
            if (s.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                builder.suggest(s);
            }
        }
        return builder.buildFuture();
    }
}
