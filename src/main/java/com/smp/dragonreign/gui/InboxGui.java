package com.smp.dragonreign.gui;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.Perms;
import com.smp.dragonreign.inbox.Inbox;
import com.smp.dragonreign.inbox.InboxEntry;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Scheduling;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Paginated, severity-coloured browser over the staff inbox. Unread alerts glow.
 * Left-click marks an alert read; shift-click dismisses it outright. Mirrors the shape
 * of {@link HistoryGui} so the two read the same.
 */
public final class InboxGui {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT = 53;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DragonReign plugin;

    public InboxGui(DragonReign plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, int page) {
        Inbox inbox = plugin.inbox();
        int totalPages = inbox.totalPages(PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        GuiHolder holder = new GuiHolder(GuiHolder.Type.INBOX, page);
        int unread = inbox.unreadCount();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Msg.mm("<dark_purple>Staff Inbox</dark_purple> <gray>(" + unread + " unread)</gray>"));
        holder.attach(inv);

        List<InboxEntry> entries = inbox.recentNewestFirst(page, PAGE_SIZE);
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
        inv.setItem(SLOT_INFO, Items.of(Material.BOOK,
                Msg.mm("<light_purple>Staff Inbox</light_purple>"),
                List.of(
                        Msg.mm("<gray>Newest first · " + unread + " unread</gray>"),
                        Msg.mm("<gray>Page " + (page + 1) + " of " + totalPages + "</gray>"),
                        Msg.mm("<yellow>Left-click</yellow> <gray>an alert to mark it read</gray>"),
                        Msg.mm("<yellow>Shift-click</yellow> <gray>to dismiss it</gray>"))));

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

    private ItemStack toItem(InboxEntry e) {
        String color = e.severity().color();
        Component name = Msg.mm("<" + color + ">" + e.severity().name() + "</" + color + "> "
                + "<white>" + Msg.escape(e.type()) + "</white> "
                + "<dark_gray>(" + relative(e.epochMillis()) + ")</dark_gray>");

        List<Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>" + Msg.escape(e.message()) + "</gray>"));
        if (!e.related().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (UUID u : e.related()) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                names.add(op.getName() != null ? op.getName() : u.toString().substring(0, 8));
            }
            lore.add(Msg.mm("<dark_gray>Players: <gray>" + Msg.escape(String.join(", ", names)) + "</gray></dark_gray>"));
        }
        lore.add(Msg.mm("<dark_gray>When: <gray>" + STAMP.format(Instant.ofEpochMilli(e.epochMillis())) + "</gray></dark_gray>"));
        lore.add(Msg.mm(e.read()
                ? "<dark_gray>Read</dark_gray>"
                : "<yellow>Unread — click to mark read, shift-click to dismiss</yellow>"));

        // Unread alerts glow so the eye lands on them first.
        return Items.of(e.severity().icon(), name, lore, !e.read());
    }

    public void handleClick(Player player, int currentPage, int slot, ClickType click) {
        if (slot == SLOT_PREV) {
            Scheduling.later(plugin, () -> open(player, currentPage - 1), 1L);
            return;
        }
        if (slot == SLOT_NEXT) {
            Scheduling.later(plugin, () -> open(player, currentPage + 1), 1L);
            return;
        }
        if (slot == SLOT_BACK) {
            if (player.hasPermission(Perms.GUI)) {
                Scheduling.later(plugin, () -> plugin.configGui().open(player), 1L);
            } else {
                player.closeInventory();
            }
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) {
            return; // clicked the footer / a blank slot
        }
        // Re-fetch the page so we act on the entry the player is actually looking at,
        // even if a new alert arrived between render and click.
        List<InboxEntry> entries = plugin.inbox().recentNewestFirst(currentPage, PAGE_SIZE);
        if (slot >= entries.size()) {
            return;
        }
        InboxEntry entry = entries.get(slot);
        if (click.isShiftClick()) {
            plugin.inbox().dismiss(entry.id());
        } else {
            plugin.inbox().markRead(entry.id());
        }
        plugin.saveAsync();
        Scheduling.later(plugin, () -> open(player, currentPage), 1L);
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
        return (hours / 24) + "d ago";
    }
}
