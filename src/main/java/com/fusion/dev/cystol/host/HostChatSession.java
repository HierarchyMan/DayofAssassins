package com.fusion.dev.cystol.host;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.util.TimeUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the next chat line as a UTC schedule time for {@link HostGui}.
 */
public final class HostChatSession implements Listener {

    private final JavaPlugin plugin;
    private final Lang lang;
    private final Map<UUID, HostGui.TimeField> pending = new ConcurrentHashMap<>();
    private volatile HostGui hostGui;

    public HostChatSession(JavaPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public void bindGui(HostGui hostGui) {
        this.hostGui = hostGui;
    }

    public void begin(Player player, HostGui.TimeField field) {
        if (player == null || field == null) {
            return;
        }
        pending.put(player.getUniqueId(), field);
    }

    public void cancel(UUID id) {
        if (id != null) {
            pending.remove(id);
        }
    }

    public boolean isPending(UUID id) {
        return id != null && pending.containsKey(id);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        HostGui.TimeField field = pending.get(player.getUniqueId());
        if (field == null) {
            return;
        }
        event.setCancelled(true);
        String raw = event.getMessage() == null ? "" : event.getMessage().trim();
        pending.remove(player.getUniqueId());

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (raw.equalsIgnoreCase("cancel") || raw.equalsIgnoreCase("c")) {
                player.sendMessage(lang.msg("host.gui.times.cancelled"));
                reopenTimes(player);
                return;
            }
            if (field == HostGui.TimeField.FFA
                    && (raw.equalsIgnoreCase("clear") || raw.equalsIgnoreCase("none"))) {
                HostGui gui = hostGui;
                if (gui != null) {
                    gui.clearTime(field);
                }
                player.sendMessage(lang.msg("host.gui.times.cleared", Map.of("field", "finale")));
                reopenTimes(player);
                return;
            }
            Optional<Instant> parsed = TimeUtil.parseScheduleInput(raw, Instant.now());
            if (parsed.isEmpty()) {
                player.sendMessage(lang.msg("host.gui.times.invalid", Map.of(
                        "format", "yyyy/MM/dd HH:mm:ss  or  -r 1d2h5m"
                )));
                // re-arm so they can try again
                pending.put(player.getUniqueId(), field);
                return;
            }
            HostGui gui = hostGui;
            if (gui != null) {
                gui.applyTime(field, parsed.get());
            }
            player.sendMessage(lang.msg("host.gui.times.set-ok", Map.of(
                    "field", field == HostGui.TimeField.START ? "start"
                            : field == HostGui.TimeField.END ? "end" : "finale",
                    "time", TimeUtil.formatUtc(parsed.get())
            )));
            reopenTimes(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    private void reopenTimes(Player player) {
        HostGui gui = hostGui;
        if (gui != null && player.isOnline()) {
            gui.openTimes(player);
        }
    }
}
