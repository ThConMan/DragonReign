package com.smp.dragonreign.command;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.Perms;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.util.Egg;
import com.smp.dragonreign.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** /giveegg &lt;player&gt; — hand the egg you're holding to someone else, transferring ownership. */
public final class GiveEggCommand implements TabExecutor {

    private final DragonReign plugin;

    public GiveEggCommand(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.config();
        if (!sender.hasPermission(Perms.GIVEEGG)) {
            return true; // silent — no reply without the permission
        }
        if (!(sender instanceof Player giver)) {
            sender.sendMessage(Msg.prefixed(config.getPrefix(), "<red>Only players can give the egg.</red>"));
            return true;
        }
        if (args.length != 1) {
            giver.sendMessage(Msg.prefixed(config.getPrefix(), "<gray>Usage: <white>/giveegg <player></white></gray>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            giver.sendMessage(Msg.prefixed(config.getPrefix(), "<red>That player isn't online.</red>"));
            return true;
        }
        if (target.equals(giver)) {
            giver.sendMessage(Msg.prefixed(config.getPrefix(), "<red>You already hold the egg.</red>"));
            return true;
        }

        ItemStack inHand = giver.getInventory().getItemInMainHand();
        if (!Egg.isDragonEgg(inHand)) {
            giver.sendMessage(Msg.prefixed(config.getPrefix(), "<red>You must be holding the Dragon Egg.</red>"));
            return true;
        }

        // Take one egg from the giver's hand.
        inHand.setAmount(inHand.getAmount() - 1);
        giver.getInventory().setItemInMainHand(inHand.getAmount() > 0 ? inHand : null);

        // Hand it over; drop at their feet if their inventory is full (shared helper).
        Egg.giveOrDrop(target, 1);

        plugin.store().setOwner(target.getUniqueId(), "transfer via /giveegg");
        plugin.store().clearLocation();
        plugin.store().touchActivity();
        plugin.history().append(EventType.TRANSFER, giver, null,
                "gave the egg to " + target.getName());
        plugin.saveAsync();

        giver.sendMessage(Msg.prefixed(config.getPrefix(), "<green>You handed the Dragon Egg to <white>" + target.getName() + "</white>.</green>"));
        target.sendMessage(Msg.prefixed(config.getPrefix(), "<light_purple>" + giver.getName() + " entrusted you with the Dragon Egg.</light_purple>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(sender) && p.getName().toLowerCase().startsWith(prefix)) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
