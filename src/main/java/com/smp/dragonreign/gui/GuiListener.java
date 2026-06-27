package com.smp.dragonreign.gui;

import com.smp.dragonreign.DragonReign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Routes clicks in our own GUIs and makes sure nothing can be looted out of them. */
public final class GuiListener implements Listener {

    private final DragonReign plugin;

    public GuiListener(DragonReign plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true); // our GUIs are display-only

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Only act on clicks within the GUI itself, not the player's inventory below.
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(top)) {
            return;
        }

        switch (holder.type()) {
            case CONFIG -> plugin.configGui().handleClick(player, top, event.getSlot(), event.getClick());
            case HISTORY -> plugin.historyGui().handleClick(player, holder.page(), event.getSlot());
            case INBOX -> plugin.inboxGui().handleClick(player, holder.page(), event.getSlot(), event.getClick());
            case COSMETICS -> plugin.cosmeticsGui().handleClick(player, top, event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }
}
