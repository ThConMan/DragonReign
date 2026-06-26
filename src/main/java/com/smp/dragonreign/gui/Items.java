package com.smp.dragonreign.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Builds GUI items with non-italic names/lore (vanilla italicizes custom names by default). */
final class Items {

    private Items() {
    }

    static ItemStack of(Material material, Component name, List<Component> lore) {
        return of(material, name, lore, false);
    }

    /** A blank, neutral pane for framing the empty slots of a GUI. */
    static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), null);
    }

    /** As above, plus an optional enchant-glint (used to mark unread inbox alerts). */
    static ItemStack of(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) {
                List<Component> clean = new ArrayList<>(lore.size());
                for (Component line : lore) {
                    clean.add(line.decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(clean);
            }
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
