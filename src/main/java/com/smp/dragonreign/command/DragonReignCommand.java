package com.smp.dragonreign.command;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.Perms;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Players;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /dragonreign (dreign, degg) — admin GUIs, reload, and an info readout. */
public final class DragonReignCommand implements TabExecutor {

    private final DragonReign plugin;

    public DragonReignCommand(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.config();
        if (args.length == 0) {
            showInfo(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "gui" -> {
                if (!sender.hasPermission(Perms.GUI)) {
                    return true; // silent — no reply for the unauthorized
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                plugin.configGui().open(player);
            }
            case "log", "history" -> {
                if (!sender.hasPermission(Perms.HISTORY)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                plugin.historyGui().open(player, 0);
            }
            case "inbox" -> {
                if (!sender.hasPermission(Perms.INBOX)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                plugin.inboxGui().open(player, 0);
            }
            case "respawn" -> {
                if (!sender.hasPermission(Perms.RESPAWN)) {
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("force")) {
                    plugin.countdown().forceNow(sender);
                } else if (plugin.store().getOwner() == null) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(),
                            "<red>The egg is unowned — nothing to respawn. (Use <white>force</white> only when it's claimed.)</red>"));
                } else {
                    boolean started = plugin.countdown().requestRespawn(plugin.store().getOwner());
                    if (!started) {
                        sender.sendMessage(Msg.prefixed(config.getPrefix(),
                                "<yellow>A countdown is already running (" + plugin.countdown().secondsLeft()
                                        + "s left) — use <white>/dragonreign cancel</white> or "
                                        + "<white>/dragonreign respawn force</white>.</yellow>"));
                    } else {
                        sender.sendMessage(Msg.prefixed(config.getPrefix(),
                                plugin.config().isCountdownEnabled()
                                        ? "<green>Respawn countdown started.</green>"
                                        : "<green>Egg respawned (countdown disabled).</green>"));
                    }
                }
            }
            case "cancel" -> {
                if (!sender.hasPermission(Perms.CANCEL)) {
                    return true;
                }
                plugin.countdown().cancelByAdmin(sender);
            }
            case "reload" -> {
                if (!sender.hasPermission(Perms.RELOAD)) {
                    return true;
                }
                plugin.reloadEverything();
                sender.sendMessage(Msg.prefixed(config.getPrefix(), "<green>Configuration reloaded.</green>"));
            }
            case "cosmetics" -> {
                if (!sender.hasPermission(Perms.COSMETICS)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                if (!plugin.victors().isVictor(player)) {
                    player.sendMessage(Msg.prefixed(config.getPrefix(),
                            "<red>Only Dragonlords have cosmetics to change.</red>"));
                    return true;
                }
                plugin.cosmeticsGui().open(player);
            }
            case "particle" -> toggleCosmetic(sender, true);
            case "title" -> toggleCosmetic(sender, false);
            case "victor" -> handleVictor(sender, args);
            case "guide", "book", "help" -> {
                if (!sender.hasPermission(Perms.GUIDE)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                GuideBook.give(player);
                player.sendMessage(Msg.prefixed(config.getPrefix(), "<green>The Dragon Egg guide is in your inventory.</green>"));
            }
            case "info" -> showInfo(sender);
            case "admininfo", "ainfo", "status" -> showAdminInfo(sender);
            default -> {
                // Only hint at the syntax for someone who could use it; everyone else gets nothing.
                if (sender.hasPermission(Perms.ADMIN)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(),
                            "<red>Unknown subcommand. Try <white>gui</white>, <white>log</white>, <white>inbox</white>, "
                                    + "<white>respawn</white>, <white>cancel</white>, <white>reload</white>, "
                                    + "<white>victor</white>, <white>cosmetics</white>, <white>info</white>, "
                                    + "or <white>admininfo</white>.</red>"));
                } else if (sender.hasPermission(Perms.COSMETICS)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(),
                            "<red>Unknown subcommand. Try <white>cosmetics</white>, <white>particle</white>, "
                                    + "<white>title</white>, or <white>info</white>.</red>"));
                }
            }
        }
        return true;
    }

    /** Flip the caller's own aura ({@code particle=true}) or title ({@code particle=false}). */
    private void toggleCosmetic(CommandSender sender, boolean particle) {
        ConfigManager config = plugin.config();
        if (!sender.hasPermission(Perms.COSMETICS)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
            return;
        }
        if (!plugin.victors().isVictor(player)) {
            player.sendMessage(Msg.prefixed(config.getPrefix(),
                    "<red>Only Dragonlords have cosmetics to change.</red>"));
            return;
        }
        UUID id = player.getUniqueId();
        boolean now;
        if (particle) {
            now = !plugin.victors().particleEnabled(id);
            plugin.victors().setParticleEnabled(id, now);
        } else {
            now = !plugin.victors().titleEnabled(id);
            plugin.victors().setTitleEnabled(id, now);
        }
        plugin.saveAsync();
        player.sendMessage(Msg.prefixed(config.getPrefix(),
                "<green>" + (particle ? "Particle aura" : "Title") + " turned "
                        + (now ? "<white>on</white>" : "<white>off</white>") + ".</green>"));
    }

    /** {@code /dr victor <grant|revoke> <player>} — admin only. */
    private void handleVictor(CommandSender sender, String[] args) {
        ConfigManager config = plugin.config();
        if (!sender.hasPermission(Perms.CMD_VICTOR)) {
            return;
        }
        if (args.length < 3 || (!args[1].equalsIgnoreCase("grant") && !args[1].equalsIgnoreCase("revoke"))) {
            sender.sendMessage(Msg.prefixed(config.getPrefix(),
                    "<gray>Usage: <white>/dr victor <grant|revoke> <player></white></gray>"));
            return;
        }
        org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            target = org.bukkit.Bukkit.getOfflinePlayerIfCached(args[2]);
        }
        if (target == null) {
            sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Never seen a player named "
                    + Msg.escape(args[2]) + ".</red>"));
            return;
        }
        if (args[1].equalsIgnoreCase("grant")) {
            plugin.victors().adminGrant(sender, target.getUniqueId());
            sender.sendMessage(Msg.prefixed(config.getPrefix(),
                    "<green>Granted Dragonlord to <white>" + Msg.escape(args[2]) + "</white>.</green>"));
        } else {
            plugin.victors().adminRevoke(sender, target.getUniqueId());
            sender.sendMessage(Msg.prefixed(config.getPrefix(),
                    "<green>Revoked Dragonlord from <white>" + Msg.escape(args[2]) + "</white>.</green>"));
        }
        plugin.saveAsync();
    }

    /** Show the info readout, but only to someone who holds the (default-true) info node. */
    private void showInfo(CommandSender sender) {
        if (!sender.hasPermission(Perms.INFO)) {
            return; // silent
        }
        sendPlayerInfo(sender);
    }

    private void showAdminInfo(CommandSender sender) {
        if (!sender.hasPermission(Perms.ADMININFO)) {
            return; // silent
        }
        sendAdminInfo(sender);
    }

    /**
     * The friendly, player-facing readout: who holds the egg, whether there's a respawn race
     * on, and a nudge to the guide. No coordinates, no config jargon — the egg's location stays
     * secret on purpose (the whole point is to physically contest it).
     */
    private void sendPlayerInfo(CommandSender sender) {
        ConfigManager c = plugin.config();
        UUID owner = plugin.store().getOwner();
        sender.sendMessage(Msg.prefixed(c.getPrefix(), "<dark_purple>— The Dragon Egg —</dark_purple>"));
        if (owner == null) {
            sender.sendMessage(Msg.mm("<gray>No one holds the egg right now — it's out there, "
                    + "waiting to be claimed.</gray>"));
        } else {
            boolean lord = plugin.victors().isVictor(owner);
            sender.sendMessage(Msg.mm("<gray>The egg belongs to <white>" + Players.name(owner) + "</white>"
                    + (lord ? " <dark_purple>— a Dragonlord</dark_purple>" : "") + ".</gray>"));
            long reset = plugin.resetRemainingMillis();
            if (reset >= 0) {
                sender.sendMessage(Msg.mm("<gray>If left alone, it resets at the End in <white>"
                        + com.smp.dragonreign.util.Times.human(reset)
                        + "</white> — stay active to keep it.</gray>"));
            }
        }
        if (plugin.countdown().secondsLeft() > 0) {
            sender.sendMessage(Msg.mm("<gold>The egg respawns at the End in <white>"
                    + plugin.countdown().secondsLeft() + "s</white> — race there!</gold>"));
        }
        sender.sendMessage(Msg.mm("<dark_gray>Read <white>/dr guide</white> to learn how it works.</dark_gray>"));
    }

    /** The technical staff readout: owner, rules, exact location, timers, ownership posture, inbox. */
    private void sendAdminInfo(CommandSender sender) {
        ConfigManager c = plugin.config();
        UUID owner = plugin.store().getOwner();
        String ownerName = owner == null ? "none (unclaimed)" : Players.name(owner);

        sender.sendMessage(Msg.prefixed(c.getPrefix(), "<dark_purple>— Dragon Egg (admin) —</dark_purple>"));
        sender.sendMessage(Msg.mm("<gray>Owner: <white>" + ownerName + "</white></gray>"));
        sender.sendMessage(Msg.mm("<gray>Rules — containers: " + onOff(c.isNoContainers())
                + "<gray>, drop: " + onOff(c.isNoDrop())
                + "<gray>, sweep: " + onOff(c.isEnderSweepEnabled())
                + "<gray>, respawn: " + onOff(c.isRespawnEnabled())
                + "<gray>, announce: " + onOff(c.isAnnounceEnabled()) + "</gray>"));
        sender.sendMessage(Msg.mm("<gray>Countdown: <white>" + plugin.countdown().statusText() + "</white></gray>"));

        EggLocation loc = plugin.store().getLocation();
        sender.sendMessage(Msg.mm("<gray>Placed at: <white>" + (loc != null ? loc.compact() : "not placed (held or gone)") + "</white></gray>"));
        sender.sendMessage(Msg.mm("<gray>Inactivity: <white>" + c.getInactivityDays() + "d</white>"
                + "<gray>, sound: <white>" + c.getSoundMode().name() + "</white></gray>"));
        sender.sendMessage(Msg.mm("<gray>Resets in — inactivity: <white>"
                + com.smp.dragonreign.util.Times.human(plugin.inactivityRemainingMillis())
                + "</white><gray>, staleness: <white>"
                + com.smp.dragonreign.util.Times.human(plugin.stalenessRemainingMillis()) + "</white></gray>"));
        sender.sendMessage(Msg.mm("<gray>Last activity: <white>" + STAMP.format(
                java.time.Instant.ofEpochMilli(plugin.store().getLastActivity())) + "</white></gray>"));
        String strict;
        if (!c.isStrictOwnershipEnabled()) {
            strict = "<red>off</red>";
        } else {
            strict = "<green>on</green> <gray>(" + (c.isAutoEnforceIpLinks() ? "enforce" : "flag")
                    + (c.isCheckIpAlts() ? ", ip-alts" : "") + ")</gray>";
        }
        sender.sendMessage(Msg.mm("<gray>Strict ownership: " + strict + "</gray>"));
        int unread = plugin.inbox().unreadCount();
        sender.sendMessage(Msg.mm("<gray>Inbox: <white>" + unread + " unread</white>"
                + (unread > 0 ? " <yellow>→ /dragonreign inbox</yellow>" : "") + "</gray>"));
    }

    private static final java.time.format.DateTimeFormatter STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    private String onOff(boolean on) {
        return on ? "<green>on</green>" : "<red>off</red>";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // Only suggest what the sender can actually run — no probing the command's shape.
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission(Perms.INFO)) subs.add("info");
            if (sender.hasPermission(Perms.ADMININFO)) subs.add("admininfo");
            if (sender.hasPermission(Perms.GUIDE)) subs.add("guide");
            if (sender.hasPermission(Perms.GUI)) subs.add("gui");
            if (sender.hasPermission(Perms.HISTORY)) {
                subs.add("log");
                subs.add("history");
            }
            if (sender.hasPermission(Perms.INBOX)) subs.add("inbox");
            if (sender.hasPermission(Perms.RESPAWN)) subs.add("respawn");
            if (sender.hasPermission(Perms.CANCEL)) subs.add("cancel");
            if (sender.hasPermission(Perms.RELOAD)) subs.add("reload");
            if (sender.hasPermission(Perms.CMD_VICTOR)) subs.add("victor");
            if (sender.hasPermission(Perms.COSMETICS)) {
                subs.add("cosmetics");
                subs.add("particle");
                subs.add("title");
            }

            String prefix = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : subs) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        // `/dr respawn <force>`
        if (args.length == 2 && args[0].equalsIgnoreCase("respawn") && sender.hasPermission(Perms.RESPAWN)) {
            if ("force".startsWith(args[1].toLowerCase())) {
                return List.of("force");
            }
        }
        // `/dr victor <grant|revoke> <player>`
        if (args[0].equalsIgnoreCase("victor") && sender.hasPermission(Perms.CMD_VICTOR)) {
            if (args.length == 2) {
                List<String> out = new ArrayList<>();
                for (String s : List.of("grant", "revoke")) {
                    if (s.startsWith(args[1].toLowerCase())) {
                        out.add(s);
                    }
                }
                return out;
            }
            if (args.length == 3) {
                String p = args[2].toLowerCase();
                List<String> names = new ArrayList<>();
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    if (online.getName().toLowerCase().startsWith(p)) {
                        names.add(online.getName());
                    }
                }
                return names;
            }
        }
        return List.of();
    }
}
