package com.smp.dragonreign.gui;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** A small menu where Dragonlords switch their own aura and title on or off. */
public final class CosmeticsGui {

    private static final int SLOT_PARTICLE = 11;
    private static final int SLOT_TITLE = 13;
    private static final int SLOT_CLOSE = 15;

    private final DragonReign plugin;

    public CosmeticsGui(DragonReign plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.COSMETICS, 0);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Msg.mm("<dark_purple>Dragonlord Cosmetics</dark_purple>"));
        holder.attach(inv);
        render(player, inv);
        player.openInventory(inv);
    }

    private void render(Player player, Inventory inv) {
        inv.clear();
        java.util.UUID id = player.getUniqueId();
        boolean particleOn = plugin.victors().particleEnabled(id);
        boolean titleOn = plugin.victors().titleEnabled(id);

        inv.setItem(SLOT_PARTICLE, toggle(particleOn, Material.HEART_OF_THE_SEA, "Particle Aura",
                "Show sparkles around you."));
        inv.setItem(SLOT_TITLE, toggle(titleOn, Material.NAME_TAG, "Title",
                "Show your Dragonlord title in chat and the tab list."));
        inv.setItem(SLOT_CLOSE, Items.of(Material.BARRIER,
                Msg.mm("<red>Close</red>"), List.of(Msg.mm("<gray>Close this menu</gray>"))));

        ItemStack pane = Items.filler();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, pane);
            }
        }
    }

    private ItemStack toggle(boolean on, Material material, String label, String desc) {
        String state = on ? "<green>ON</green>" : "<red>OFF</red>";
        return Items.of(material, Msg.mm("<white>" + label + ": </white>" + state),
                List.of(Msg.mm("<gray>" + desc + "</gray>"),
                        Msg.mm("<yellow>Click to toggle</yellow>")), on);
    }

    public void handleClick(Player player, Inventory inv, int slot) {
        java.util.UUID id = player.getUniqueId();
        switch (slot) {
            case SLOT_PARTICLE -> plugin.victors().setParticleEnabled(id, !plugin.victors().particleEnabled(id));
            case SLOT_TITLE -> plugin.victors().setTitleEnabled(id, !plugin.victors().titleEnabled(id));
            case SLOT_CLOSE -> {
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        plugin.saveAsync();
        render(player, inv);
    }
}
