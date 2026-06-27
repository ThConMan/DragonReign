package com.smp.dragonreign.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder so the shared click listener can identify our GUIs by instance
 * (and page) instead of fragile title-string matching.
 */
public final class GuiHolder implements InventoryHolder {

    public enum Type { CONFIG, HISTORY, INBOX, COSMETICS }

    private final Type type;
    private final int page;
    private Inventory inventory;

    public GuiHolder(Type type, int page) {
        this.type = type;
        this.page = page;
    }

    public Type type() {
        return type;
    }

    public int page() {
        return page;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
