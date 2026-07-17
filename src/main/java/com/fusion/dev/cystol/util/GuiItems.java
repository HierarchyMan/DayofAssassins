package com.fusion.dev.cystol.util;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.components.GuiAction;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Paper-native GUI item helpers.
 *
 * <p>Triumph GUI's {@code ItemBuilder#name(Component)} / {@code lore(...)} go through
 * {@code adventure-platform-bukkit}'s {@code MinecraftComponentSerializer}, which is
 * <strong>not</strong> on Paper's plugin classpath (Paper ships Adventure natively).
 * Always set name/lore via {@link ItemMeta#displayName(Component)} / {@link ItemMeta#lore(List)}.
 */
public final class GuiItems {

    private GuiItems() {
    }

    public static ItemStack stack(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.STONE : material);
        apply(stack, name, lore);
        return stack;
    }

    public static ItemStack apply(ItemStack stack, Component name, List<Component> lore) {
        if (stack == null) {
            return new ItemStack(Material.AIR);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.displayName(name);
        }
        if (lore != null) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public static GuiItem item(
            Material material,
            Component name,
            List<Component> lore,
            GuiAction<InventoryClickEvent> action
    ) {
        ItemStack stack = stack(material, name, lore);
        if (action == null) {
            return ItemBuilder.from(stack).asGuiItem();
        }
        return ItemBuilder.from(stack).asGuiItem(action);
    }

    public static GuiItem item(
            ItemStack base,
            Component name,
            List<Component> lore,
            GuiAction<InventoryClickEvent> action
    ) {
        ItemStack stack = base == null ? new ItemStack(Material.STONE) : base.clone();
        apply(stack, name, lore);
        if (action == null) {
            return ItemBuilder.from(stack).asGuiItem();
        }
        return ItemBuilder.from(stack).asGuiItem(action);
    }
}
