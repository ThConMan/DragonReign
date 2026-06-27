package com.smp.dragonreign.gui;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.Perms;
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
    // Row 2 (slots 19-25): v2 controls + v1.2 compass/rewards.
    private static final int SLOT_COUNTDOWN = 19;
    private static final int SLOT_STRICT = 20;
    private static final int SLOT_COMPASS = 21;
    private static final int SLOT_REWARDS = 22;
    private static final int SLOT_TELEPORT = 23; // jump to the egg when it's placed
    private static final int SLOT_HISTORY = 24;  // open the history GUI
    private static final int SLOT_INBOX = 25;
    // Row 3 (slots 28-34): v1.2 controls.
    private static final int SLOT_VOID = 28;
    private static final int SLOT_AFK = 29;
    private static final int SLOT_VICTOR_AURA = 30;
    private static final int SLOT_CLOSE = 31;    // close the menu
    private static final int SLOT_VICTOR_TITLE = 32;
    private static final int SLOT_STALENESS = 33;

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
                "<dark_gray>Different people can look linked (shared homes, school or phone networks)</dark_gray>",
                "<dark_gray>Default is FLAG, not enforce — see config.yml</dark_gray>",
                "<dark_gray>Only scrambled codes are saved, never the real address</dark_gray>"));

        inv.setItem(SLOT_COMPASS, toggle(c.isCompassEnabled(), "Egg Compass",
                "Show nearby players an arrow toward the placed egg",
                "<dark_gray>Range: " + c.getCompassRadius() + " blocks</dark_gray>"));

        inv.setItem(SLOT_REWARDS, toggle(c.isRewardsEnabled(), "Hold Rewards",
                "Reward the keeper for every stretch of time they hold the egg",
                "<dark_gray>Every " + c.getRewardIntervalMinutes() + " minute(s) of active holding</dark_gray>"));

        // ── v1.2 bottom row ─────────────────────────────────────────────────
        inv.setItem(SLOT_VOID, toggle(c.isVoidSafetyEnabled(), "Void Safety",
                "Rescue the egg if it ever falls into the void"));

        inv.setItem(SLOT_AFK, toggle(c.isAfkEnabled(), "Away Check",
                "Pause reward and Dragonlord time while the keeper is away (AFK)"));

        inv.setItem(SLOT_VICTOR_AURA, toggle(c.isVictorParticleEnabled(), "Dragonlord Aura",
                "Master switch for the sparkle aura around Dragonlords"));

        inv.setItem(SLOT_VICTOR_TITLE, toggle(c.isVictorTitleEnabled(), "Dragonlord Title",
                "Master switch for the Dragonlord chat/tab title"));

        int staleness = c.getStalenessDaysRaw();
        inv.setItem(SLOT_STALENESS, Items.of(Material.COBWEB,
                Msg.mm("<aqua>Staleness Days: <white>" + (staleness == 0 ? "off" : staleness) + "</white></aqua>"),
                List.of(
                        Msg.mm("<gray>Respawn the egg if it sits untouched this long</gray>"),
                        Msg.mm("<gray>even while the owner is online. <white>0 = off</white></gray>"),
                        Msg.mm("<gray>Left-click <white>+1</white>, right-click <white>-1</white></gray>"),
                        Msg.mm("<dark_gray>Must be less than Inactivity Days</dark_gray>"))));

        int unread = plugin.inbox().unreadCount();
        inv.setItem(SLOT_INBOX, Items.of(Material.BARREL,
                Msg.mm("<light_purple>Open Staff Inbox</light_purple> <gray>(" + unread + " unread)</gray>"),
                List.of(
                        Msg.mm("<gray>View alerts: alt flags, dupes, respawns…</gray>"),
                        Msg.mm("<yellow>Click to open</yellow>")),
                unread > 0));

        inv.setItem(SLOT_HISTORY, Items.of(Material.WRITABLE_BOOK,
                Msg.mm("<light_purple>Open History</light_purple>"),
                List.of(
                        Msg.mm("<gray>Browse every egg event, newest first.</gray>"),
                        Msg.mm("<yellow>Click to open</yellow>"))));

        inv.setItem(SLOT_CLOSE, Items.of(Material.BARRIER,
                Msg.mm("<red>Close</red>"),
                List.of(Msg.mm("<gray>Close this menu</gray>"))));

        renderTeleport(inv);
        fillEmpty(inv);
    }

    /** Frame the leftover slots with neutral panes so the menu reads cleanly. */
    private void fillEmpty(Inventory inv) {
        ItemStack pane = Items.filler();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, pane);
            }
        }
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
            case SLOT_COMPASS -> c.setCompassEnabled(!c.isCompassEnabled());
            case SLOT_REWARDS -> c.setRewardsEnabled(!c.isRewardsEnabled());
            case SLOT_VOID -> c.setVoidSafetyEnabled(!c.isVoidSafetyEnabled());
            case SLOT_AFK -> c.setAfkEnabled(!c.isAfkEnabled());
            case SLOT_VICTOR_AURA -> c.setVictorParticleEnabled(!c.isVictorParticleEnabled());
            case SLOT_VICTOR_TITLE -> c.setVictorTitleEnabled(!c.isVictorTitleEnabled());
            case SLOT_DAYS -> {
                int delta = click.isShiftClick() ? 7 : 1;
                if (click.isRightClick()) {
                    delta = -delta;
                }
                c.setInactivityDays(c.getInactivityDays() + delta);
            }
            case SLOT_STALENESS -> {
                int delta = click.isRightClick() ? -1 : 1;
                c.setStalenessDays(c.getStalenessDaysRaw() + delta);
            }
            case SLOT_SOUND -> c.setSoundMode(c.getSoundMode().next());
            case SLOT_INBOX -> {
                if (!player.hasPermission(Perms.INBOX)) {
                    return; // silently ignore — no inbox access
                }
                // Reopening mid-click can desync the cursor; defer one tick (same as HistoryGui).
                Scheduling.later(plugin, () -> plugin.inboxGui().open(player, 0), 1L);
                return;
            }
            case SLOT_HISTORY -> {
                if (!player.hasPermission(Perms.HISTORY)) {
                    return;
                }
                Scheduling.later(plugin, () -> plugin.historyGui().open(player, 0), 1L);
                return;
            }
            case SLOT_TELEPORT -> {
                if (!player.hasPermission(Perms.TELEPORT)) {
                    return;
                }
                teleportToEgg(player);
                return; // the teleport closes the GUI; nothing to re-render
            }
            case SLOT_CLOSE -> {
                player.closeInventory();
                return;
            }
            default -> {
                return; // clicked a blank slot or a filler pane
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
