package com.smp.dragonreign.gui;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.Perms;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.model.HistoryEntry;
import com.smp.dragonreign.store.HistoryLog;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Scheduling;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Paginated, newest-first browser over the history log. */
public final class HistoryGui {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT = 53;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DragonReign plugin;

    public HistoryGui(DragonReign plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, int page) {
        HistoryLog log = plugin.history();
        int totalPages = log.totalPages(PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        GuiHolder holder = new GuiHolder(GuiHolder.Type.HISTORY, page);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Msg.mm("<dark_purple>Egg History</dark_purple> <gray>(page " + (page + 1) + "/" + totalPages + ")</gray>"));
        holder.attach(inv);

        List<HistoryEntry> entries = log.recentNewestFirst(page, PAGE_SIZE);
        for (int i = 0; i < entries.size(); i++) {
            inv.setItem(i, toItem(entries.get(i)));
        }

        if (page > 0) {
            inv.setItem(SLOT_PREV, Items.of(Material.ARROW,
                    Msg.mm("<yellow>« Previous</yellow>"), List.of(Msg.mm("<gray>Page " + page + "</gray>"))));
        }
        if (page < totalPages - 1) {
            inv.setItem(SLOT_NEXT, Items.of(Material.ARROW,
                    Msg.mm("<yellow>Next »</yellow>"), List.of(Msg.mm("<gray>Page " + (page + 2) + "</gray>"))));
        }
        inv.setItem(SLOT_INFO, Items.of(Material.WRITABLE_BOOK,
                Msg.mm("<light_purple>Egg History</light_purple>"),
                List.of(Msg.mm("<gray>Newest first</gray>"),
                        Msg.mm("<gray>Page " + (page + 1) + " of " + totalPages + "</gray>"))));

        inv.setItem(SLOT_BACK, Items.of(Material.OAK_DOOR,
                Msg.mm("<yellow>« Back to menu</yellow>"), List.of(Msg.mm("<gray>Return to the config menu</gray>"))));
        inv.setItem(SLOT_CLOSE, Items.of(Material.BARRIER,
                Msg.mm("<red>Close</red>"), List.of(Msg.mm("<gray>Close this menu</gray>"))));
        fillFooter(inv);

        player.openInventory(inv);
    }

    /** Pane out the unused footer slots so the nav row reads as intentional. */
    private void fillFooter(Inventory inv) {
        ItemStack pane = Items.filler();
        for (int i = 45; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, pane);
            }
        }
    }

    private ItemStack toItem(HistoryEntry e) {
        String who = e.playerName();
        Component name = Msg.mm("<light_purple>" + e.type().label() + "</light_purple> <gray>—</gray> <white>" + who + "</white>"
                + " <dark_gray>(" + relative(e.epochMillis()) + ")</dark_gray>");

        List<Component> lore = new ArrayList<>();
        if (!e.detail().isEmpty()) {
            lore.add(Msg.mm("<gray>" + escape(e.detail()) + "</gray>"));
        }
        EggLocation loc = e.location();
        lore.add(Msg.mm("<dark_gray>Where: <gray>" + (loc != null ? loc.compact() : "—") + "</gray></dark_gray>"));
        lore.add(Msg.mm("<dark_gray>When: <gray>" + STAMP.format(Instant.ofEpochMilli(e.epochMillis())) + "</gray></dark_gray>"));
        if (e.playerUuid() != null) {
            lore.add(Msg.mm("<dark_gray>UUID: <gray>" + e.playerUuid() + "</gray></dark_gray>"));
        }
        return Items.of(e.type().icon(), name, lore);
    }

    public void handleClick(Player player, int currentPage, int slot) {
        // Reopening an inventory while the InventoryClickEvent is still being dispatched
        // can desync the client cursor on some versions; defer the reopen one tick.
        if (slot == SLOT_PREV) {
            Scheduling.later(plugin, () -> open(player, currentPage - 1), 1L);
        } else if (slot == SLOT_NEXT) {
            Scheduling.later(plugin, () -> open(player, currentPage + 1), 1L);
        } else if (slot == SLOT_BACK) {
            if (player.hasPermission(Perms.GUI)) {
                Scheduling.later(plugin, () -> plugin.configGui().open(player), 1L);
            } else {
                player.closeInventory();
            }
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        }
    }

    private String relative(long then) {
        long secs = Math.max(0, (System.currentTimeMillis() - then) / 1000L);
        if (secs < 60) {
            return secs + "s ago";
        }
        long mins = secs / 60;
        if (mins < 60) {
            return mins + "m ago";
        }
        long hours = mins / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
    }

    /** MiniMessage treats '<' specially; neutralize it in user-ish detail strings. */
    private String escape(String s) {
        return s.replace("<", "\\<");
    }
}
