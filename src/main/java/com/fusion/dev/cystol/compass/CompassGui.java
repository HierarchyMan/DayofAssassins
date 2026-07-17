package com.fusion.dev.cystol.compass;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.kill.KillService;
import com.fusion.dev.cystol.util.GuiItems;
import com.fusion.dev.cystol.util.TextUtil;
import com.fusion.dev.cystol.util.VanishService;
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
import java.util.concurrent.ConcurrentHashMap;

public final class CompassGui {

    private final CompassService compassService;
    private final KillService killService;
    private final Lang lang;
    private final EffectService effects;
    private final VanishService vanishService;
    /** Base player-head with skin ownership only — name/lore applied per open. */
    private final ConcurrentHashMap<UUID, ItemStack> headBaseCache = new ConcurrentHashMap<>();

    public CompassGui(
            CompassService compassService,
            KillService killService,
            Lang lang,
            EffectService effects,
            VanishService vanishService
    ) {
        this.compassService = compassService;
        this.killService = killService;
        this.lang = lang;
        this.effects = effects;
        this.vanishService = vanishService;
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
            if (vanishService.isVanished(p)) {
                continue;
            }
            players.add(p);
        }

        UUID currentTarget = compassService.getTarget(viewer).orElse(null);

        if (players.isEmpty()) {
            Map<String, String> ph = Map.of();
            gui.setItem(22, GuiItems.item(
                    Material.BARRIER,
                    TextUtil.component(lang.raw("compass.gui.no-players.name"), ph),
                    TextUtil.componentList(lang.rawList("compass.gui.no-players.lore"), ph),
                    e -> effects.play(viewer, EffectService.EffectKey.MENU_DENY)
            ));
        } else {
            for (Player target : players) {
                gui.addItem(playerIcon(viewer, target, currentTarget));
            }
        }

        Map<String, String> pagePh = Map.of(
                "page", String.valueOf(gui.getCurrentPageNum()),
                "pages", String.valueOf(Math.max(1, gui.getPagesNum()))
        );
        gui.setItem(6, 3, GuiItems.item(
                Material.ARROW,
                TextUtil.component(lang.raw("compass.gui.prev-page.name"), pagePh),
                TextUtil.componentList(lang.rawList("compass.gui.prev-page.lore"), pagePh),
                e -> {
                    effects.play(viewer, EffectService.EffectKey.MENU_PAGE);
                    gui.previous();
                }
        ));
        gui.setItem(6, 5, GuiItems.item(
                Material.BOOK,
                TextUtil.component(lang.raw("compass.gui.info.name"), Map.of()),
                TextUtil.componentList(lang.rawList("compass.gui.info.lore"), Map.of()),
                e -> {
                }
        ));
        gui.setItem(6, 7, GuiItems.item(
                Material.ARROW,
                TextUtil.component(lang.raw("compass.gui.next-page.name"), pagePh),
                TextUtil.componentList(lang.rawList("compass.gui.next-page.lore"), pagePh),
                e -> {
                    effects.play(viewer, EffectService.EffectKey.MENU_PAGE);
                    gui.next();
                }
        ));

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

        ItemStack skull = headBase(target).clone();
        return GuiItems.item(
                skull,
                TextUtil.component(lang.raw("compass.gui.player.name"), ph),
                TextUtil.componentList(loreKeys, ph),
                event -> {
                    compassService.setTarget(viewer, target.getUniqueId());
                    effects.play(viewer, EffectService.EffectKey.MENU_SELECT_TARGET);
                    viewer.sendMessage(lang.msg("compass.tracking-selected", Map.of("target", target.getName())));
                    viewer.closeInventory();
                }
        );
    }

    private ItemStack headBase(Player target) {
        return headBaseCache.computeIfAbsent(target.getUniqueId(), id -> {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setOwningPlayer(target);
            skull.setItemMeta(meta);
            return skull;
        });
    }

    public void evictHead(UUID uuid) {
        if (uuid != null) {
            headBaseCache.remove(uuid);
        }
    }
}
