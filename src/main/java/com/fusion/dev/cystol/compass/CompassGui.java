package com.fusion.dev.cystol.compass;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.util.TextUtil;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CompassGui {

    private final CompassService compassService;
    private final KillService killService;
    private final Lang lang;
    private final EffectService effects;

    public CompassGui(CompassService compassService, KillService killService, Lang lang, EffectService effects) {
        this.compassService = compassService;
        this.killService = killService;
        this.lang = lang;
        this.effects = effects;
    }

    public void open(Player viewer) {
        Component title = TextUtil.component(lang.raw("compass.gui.title"), Map.of());
        PaginatedGui gui = Gui.paginated()
                .title(title)
                .rows(6)
                .pageSize(45)
                .create();

        gui.setDefaultClickAction(event -> event.setCancelled(true));

        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }
            if (p.isInvisible()) {
                // basic vanish-ish; real vanish plugins set metadata — still list if not metadata
            }
            // SuperVanish etc. often use Metadata "vanished"
            if (p.hasMetadata("vanished") && !p.getMetadata("vanished").isEmpty()
                    && p.getMetadata("vanished").getFirst().asBoolean()) {
                continue;
            }
            players.add(p);
        }

        UUID currentTarget = compassService.getTarget(viewer).orElse(null);

        if (players.isEmpty()) {
            Map<String, String> ph = Map.of();
            GuiItem empty = ItemBuilder.from(Material.BARRIER)
                    .name(TextUtil.component(lang.raw("compass.gui.no-players.name"), ph))
                    .lore(TextUtil.componentList(lang.rawList("compass.gui.no-players.lore"), ph))
                    .asGuiItem(e -> effects.play(viewer, EffectService.EffectKey.MENU_DENY));
            gui.setItem(22, empty);
        } else {
            for (Player target : players) {
                gui.addItem(playerIcon(viewer, target, currentTarget));
            }
        }

        // nav
        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW)
                .name(TextUtil.component(lang.raw("compass.gui.prev-page.name"),
                        Map.of("page", String.valueOf(gui.getCurrentPageNum()),
                                "pages", String.valueOf(Math.max(1, gui.getPagesNum())))))
                .lore(TextUtil.componentList(lang.rawList("compass.gui.prev-page.lore"),
                        Map.of("page", String.valueOf(gui.getCurrentPageNum()),
                                "pages", String.valueOf(Math.max(1, gui.getPagesNum())))))
                .asGuiItem(e -> {
                    effects.play(viewer, EffectService.EffectKey.MENU_PAGE);
                    gui.previous();
                }));
        gui.setItem(6, 5, ItemBuilder.from(Material.BOOK)
                .name(TextUtil.component(lang.raw("compass.gui.info.name"), Map.of()))
                .lore(TextUtil.componentList(lang.rawList("compass.gui.info.lore"), Map.of()))
                .asGuiItem(e -> {
                }));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW)
                .name(TextUtil.component(lang.raw("compass.gui.next-page.name"),
                        Map.of("page", String.valueOf(gui.getCurrentPageNum()),
                                "pages", String.valueOf(Math.max(1, gui.getPagesNum())))))
                .lore(TextUtil.componentList(lang.rawList("compass.gui.next-page.lore"),
                        Map.of("page", String.valueOf(gui.getCurrentPageNum()),
                                "pages", String.valueOf(Math.max(1, gui.getPagesNum())))))
                .asGuiItem(e -> {
                    effects.play(viewer, EffectService.EffectKey.MENU_PAGE);
                    gui.next();
                }));

        gui.open(viewer);
    }

    private GuiItem playerIcon(Player viewer, Player target, UUID currentTarget) {
        Map<String, String> ph = new HashMap<>();
        ph.put("player", target.getName());
        ph.put("world", target.getWorld().getName());
        ph.put("kills", String.valueOf(killService.getKills(target.getUniqueId())));

        boolean selected = target.getUniqueId().equals(currentTarget);
        List<String> loreKeys = selected
                ? lang.rawList("compass.gui.player.lore-selected")
                : lang.rawList("compass.gui.player.lore");

        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(target);
        meta.displayName(TextUtil.component(lang.raw("compass.gui.player.name"), ph));
        meta.lore(TextUtil.componentList(loreKeys, ph));
        skull.setItemMeta(meta);

        return ItemBuilder.from(skull).asGuiItem(event -> {
            compassService.setTarget(viewer, target.getUniqueId());
            effects.play(viewer, EffectService.EffectKey.MENU_SELECT_TARGET);
            viewer.sendMessage(TextUtil.component("&7Tracking: &f%target%", Map.of("target", target.getName())));
            viewer.closeInventory();
        });
    }
}
