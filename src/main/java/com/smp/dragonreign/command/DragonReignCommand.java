package com.smp.dragonreign.command;

import com.smp.dragonreign.DragonReign;
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

    private static final String ADMIN = "dragonreign.admin";

    private final DragonReign plugin;

    public DragonReignCommand(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.config();
        if (args.length == 0) {
            sender.sendMessage(Msg.prefixed(config.getPrefix(),
                    "<gray>Subcommands: <white>gui</white>, <white>log</white>, <white>inbox</white>, "
                            + "<white>respawn [force]</white>, <white>cancel</white>, <white>reload</white>, <white>info</white></gray>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "gui" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                plugin.configGui().open(player);
            }
            case "log", "history" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                plugin.historyGui().open(player, 0);
            }
            case "inbox" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Players only.</red>"));
                    return true;
                }
                plugin.inboxGui().open(player, 0);
            }
            case "respawn" -> {
                if (!requireAdmin(sender)) {
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
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.countdown().cancelByAdmin(sender);
            }
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadEverything();
                sender.sendMessage(Msg.prefixed(config.getPrefix(), "<green>Configuration reloaded.</green>"));
            }
            case "info" -> sendInfo(sender);
            default -> sender.sendMessage(Msg.prefixed(config.getPrefix(),
                    "<red>Unknown subcommand. Try <white>gui</white>, <white>log</white>, <white>inbox</white>, "
                            + "<white>respawn</white>, <white>cancel</white>, <white>reload</white>, or <white>info</white>.</red>"));
        }
        return true;
    }

    private void sendInfo(CommandSender sender) {
        ConfigManager c = plugin.config();
        boolean admin = sender.hasPermission(ADMIN);
        UUID owner = plugin.store().getOwner();
        String ownerName = owner == null ? "none (unclaimed)" : Players.name(owner);

        // ── Public view: who holds it and which rules are on. No coordinates, no
        //    staff-facing detail. On an SMP whose premise is the egg must be physically
        //    contested, broadcasting the keeper's base location to everyone defeats it.
        sender.sendMessage(Msg.prefixed(c.getPrefix(), "<dark_purple>— Dragon Egg —</dark_purple>"));
        sender.sendMessage(Msg.mm("<gray>Owner: <white>" + ownerName + "</white></gray>"));
        sender.sendMessage(Msg.mm("<gray>Rules — containers: " + onOff(c.isNoContainers())
                + "<gray>, drop: " + onOff(c.isNoDrop())
                + "<gray>, sweep: " + onOff(c.isEnderSweepEnabled())
                + "<gray>, respawn: " + onOff(c.isRespawnEnabled())
                + "<gray>, announce: " + onOff(c.isAnnounceEnabled()) + "</gray>"));
        // Countdown is a server-wide, already-broadcast event, so it's fine for everyone.
        sender.sendMessage(Msg.mm("<gray>Countdown: <white>" + plugin.countdown().statusText() + "</white></gray>"));

        if (!admin) {
            return;
        }

        // ── Admin-only: exact location, timers, strict-ownership posture, inbox count.
        EggLocation loc = plugin.store().getLocation();
        sender.sendMessage(Msg.mm("<gray>Placed at: <white>" + (loc != null ? loc.compact() : "not placed (held or gone)") + "</white></gray>"));
        sender.sendMessage(Msg.mm("<gray>Inactivity: <white>" + c.getInactivityDays() + "d</white>"
                + "<gray>, sound: <white>" + c.getSoundMode().name() + "</white></gray>"));
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

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        sender.sendMessage(Msg.prefixed(plugin.config().getPrefix(), "<red>You don't have permission for that.</red>"));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("info");
            if (sender.hasPermission(ADMIN)) {
                subs.add("gui");
                subs.add("log");
                subs.add("history");
                subs.add("inbox");
                subs.add("respawn");
                subs.add("cancel");
                subs.add("reload");
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
        // `/dragonreign respawn <force>`
        if (args.length == 2 && args[0].equalsIgnoreCase("respawn") && sender.hasPermission(ADMIN)) {
            if ("force".startsWith(args[1].toLowerCase())) {
                return List.of("force");
            }
        }
        return List.of();
    }
}
