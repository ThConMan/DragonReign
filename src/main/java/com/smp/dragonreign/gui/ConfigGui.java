package com.smp.dragonreign.gui;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.announce.SoundMode;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Scheduling;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/** Live, clickable view of the protection toggles and the respawn knobs. */
public final class ConfigGui {

    // Row 1 (slots 10-16): the original protection + respawn controls.
    private static final int SLOT_CONTAINERS = 10;
    private static final int SLOT_DROP = 11;
    private static final int SLOT_SWEEP = 12;
    private static final int SLOT_RESPAWN = 13;
    private static final int SLOT_ANNOUNCE = 14;
    private static final int SLOT_DAYS = 15;
    private static final int SLOT_SOUND = 16;
    // Row 2 (slots 19-25): v2 controls.
    private static final int SLOT_COUNTDOWN = 19;
    private static final int SLOT_STRICT = 20;
    private static final int SLOT_INBOX = 25;
    private static final int SLOT_TELEPORT = 23; // jump to the egg when it's placed

    private final DragonReign plugin;

    public ConfigGui(DragonReign plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.CONFIG, 0);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, 36, Msg.mm("<dark_purple>DragonReign — Settings</dark_purple>"));
        holder.attach(inv);
        render(inv);
        player.openInventory(inv);
    }

    private void render(Inventory inv) {
        ConfigManager c = plugin.config();
        inv.clear();

        inv.setItem(SLOT_CONTAINERS, toggle(c.isNoContainers(), "No Containers",
                "Block the egg from chests, the ender chest, and bundles."));
        inv.setItem(SLOT_DROP, toggle(c.isNoDrop(), "No Drop",
                "Stop players from dropping the egg."));
        inv.setItem(SLOT_SWEEP, toggle(c.isEnderSweepEnabled(), "Ender Chest Sweep",
                "Periodically reclaim eggs from ender chests."));
        inv.setItem(SLOT_RESPAWN, toggle(c.isRespawnEnabled(), "Respawn On Inactivity",
                "Respawn the egg when the owner goes inactive."));
        inv.setItem(SLOT_ANNOUNCE, toggle(c.isAnnounceEnabled(), "Announce",
                "Broadcast chat, a title, and sound on respawn."));

        inv.setItem(SLOT_DAYS, Items.of(Material.CLOCK,
                Msg.mm("<aqua>Inactivity Days: <white>" + c.getInactivityDays() + "</white></aqua>"),
                List.of(
                        Msg.mm("<gray>Left-click <white>+1</white>, right-click <white>-1</white></gray>"),
                        Msg.mm("<gray>Shift for <white>±7</white> (minimum 1)</gray>"))));

        SoundMode mode = c.getSoundMode();
        inv.setItem(SLOT_SOUND, Items.of(Material.JUKEBOX,
                Msg.mm("<gold>Sound Mode: <white>" + mode.name() + "</white></gold>"),
                List.of(
                        Msg.mm("<gray>Click to cycle</gray>"),
                        Msg.mm("<dark_gray>LIGHTNING / DRAGON_DEATH / BOTH / NONE / CUSTOM</dark_gray>"))));

        // ── v2 row ──────────────────────────────────────────────────────────
        inv.setItem(SLOT_COUNTDOWN, toggle(c.isCountdownEnabled(), "Respawn Countdown",
                "Give players a timed window to contest the egg before it respawns",
                "<dark_gray>Duration: " + c.getCountdownDurationSeconds() + "s</dark_gray>"));

        inv.setItem(SLOT_STRICT, toggle(c.isStrictOwnershipEnabled(), "Strict Ownership",
                "Flag alt/IP-linked or inactive transfers to the staff inbox",
                "<dark_gray>IP matching is imperfect (shared homes, CGNAT, VPN)</dark_gray>",
                "<dark_gray>Default is FLAG, not enforce — see config.yml</dark_gray>",
                "<dark_gray>Only salted IP hashes are stored, never raw IPs</dark_gray>"));

        int unread = plugin.inbox().unreadCount();
        inv.setItem(SLOT_INBOX, Items.of(Material.BARREL,
                Msg.mm("<light_purple>Open Staff Inbox</light_purple> <gray>(" + unread + " unread)</gray>"),
                List.of(
                        Msg.mm("<gray>View alerts: alt flags, dupes, respawns…</gray>"),
                        Msg.mm("<yellow>Click to open</yellow>")),
                unread > 0));

        renderTeleport(inv);
    }

    /** "Teleport to egg" — live coords when it's placed, greyed-out when it's being carried. */
    private void renderTeleport(Inventory inv) {
        EggLocation loc = plugin.store().getLocation();
        if (loc != null) {
            inv.setItem(SLOT_TELEPORT, Items.of(Material.ENDER_PEARL,
                    Msg.mm("<green>Teleport to Egg</green>"),
                    List.of(
                            Msg.mm("<gray>Placed at <white>" + loc.compact() + "</white></gray>"),
                            Msg.mm("<yellow>Click to teleport</yellow>")),
                    true));
        } else {
            inv.setItem(SLOT_TELEPORT, Items.of(Material.GRAY_DYE,
                    Msg.mm("<gray>Teleport to Egg</gray>"),
                    List.of(
                            Msg.mm("<dark_gray>The egg isn't placed right now</dark_gray>"),
                            Msg.mm("<dark_gray>(it's being carried or unclaimed)</dark_gray>"))));
        }
    }

    private ItemStack toggle(boolean on, String label, String desc, String... extraLore) {
        Material wool = on ? Material.LIME_WOOL : Material.RED_WOOL;
        String state = on ? "<green>ON</green>" : "<red>OFF</red>";
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Msg.mm("<gray>" + desc + "</gray>"));
        for (String extra : extraLore) {
            lore.add(Msg.mm(extra));
        }
        lore.add(Msg.mm("<yellow>Click to toggle</yellow>"));
        return Items.of(wool, Msg.mm("<white>" + label + ": " + "</white>" + state), lore);
    }

    /** Handle a click at the given slot; re-renders the open inventory afterwards. */
    public void handleClick(Player player, Inventory inv, int slot, ClickType click) {
        ConfigManager c = plugin.config();
        switch (slot) {
            case SLOT_CONTAINERS -> c.setNoContainers(!c.isNoContainers());
            case SLOT_DROP -> c.setNoDrop(!c.isNoDrop());
            case SLOT_SWEEP -> c.setEnderSweepEnabled(!c.isEnderSweepEnabled());
            case SLOT_RESPAWN -> c.setRespawnEnabled(!c.isRespawnEnabled());
            case SLOT_ANNOUNCE -> c.setAnnounceEnabled(!c.isAnnounceEnabled());
            case SLOT_COUNTDOWN -> c.setCountdownEnabled(!c.isCountdownEnabled());
            case SLOT_STRICT -> c.setStrictOwnershipEnabled(!c.isStrictOwnershipEnabled());
            case SLOT_DAYS -> {
                int delta = click.isShiftClick() ? 7 : 1;
                if (click.isRightClick()) {
                    delta = -delta;
                }
                c.setInactivityDays(c.getInactivityDays() + delta);
            }
            case SLOT_SOUND -> c.setSoundMode(c.getSoundMode().next());
            case SLOT_INBOX -> {
                // Reopening mid-click can desync the cursor; defer one tick (same as HistoryGui).
                Scheduling.later(plugin, () -> plugin.inboxGui().open(player, 0), 1L);
                return;
            }
            case SLOT_TELEPORT -> {
                teleportToEgg(player);
                return; // the teleport closes the GUI; nothing to re-render
            }
            default -> {
                return; // clicked a blank slot
            }
        }
        render(inv);
    }

    /** Send the admin to the placed egg, or tell them why we can't. */
    private void teleportToEgg(Player player) {
        ConfigManager c = plugin.config();
        EggLocation loc = plugin.store().getLocation();
        if (loc == null) {
            player.sendMessage(Msg.prefixed(c.getPrefix(),
                    "<red>The egg isn't placed right now — it's being carried or unclaimed.</red>"));
            return;
        }
        Optional<Location> live = loc.toBukkit();
        if (live.isEmpty()) {
            player.sendMessage(Msg.prefixed(c.getPrefix(),
                    "<red>The egg's world (<white>" + loc.worldName() + "</white>) isn't loaded.</red>"));
            return;
        }
        // Stand on top of the egg, centred, keeping the admin's current facing.
        Location target = live.get().add(0.5, 1.0, 0.5);
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.closeInventory();
        player.teleportAsync(target);
        player.sendMessage(Msg.prefixed(c.getPrefix(),
                "<green>Teleported to the Dragon Egg at <white>" + loc.compact() + "</white>.</green>"));
        plugin.history().append(EventType.ADMIN, player, loc, "teleported to the egg via admin GUI");
    }
}
