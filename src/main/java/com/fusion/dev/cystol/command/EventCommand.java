package com.fusion.dev.cystol.command;

import com.fusion.dev.cystol.config.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Map;

public final class EventCommand implements CommandExecutor {

    private final Lang lang;

    public EventCommand(Lang lang) {
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("preciv.event")) {
            sender.sendMessage(lang.msg("messages.no-permission"));
            return true;
        }
        for (Component line : lang.msgList("event.description", Map.of())) {
            sender.sendMessage(line);
        }
        return true;
    }
}
