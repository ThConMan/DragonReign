package com.smp.dragonreign.command;

import com.smp.dragonreign.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;
import java.util.stream.Collectors;

/** Builds the in-game "Dragon Egg" guide — a colored, paginated written book for players. */
final class GuideBook {

    private GuideBook() {
    }

    // MiniMessage pages. Kept short and plain so they read cleanly on a book page.
    private static final List<String> PAGES = List.of(
        "<bold><gradient:#9D4EDD:#F3ECFF>The Dragon Egg</gradient></bold>\n\n"
            + "<gray>One egg. The whole server wants it.\n\n"
            + "This little book explains how it works — and how to claim it.</gray>\n\n"
            + "<dark_gray>» turn the page «</dark_gray>",

        "<bold><#9D4EDD>The Rules</#9D4EDD></bold>\n\n"
            + "<gray>The egg can only ever be:</gray>\n"
            + "<white>• in your inventory\n• placed as a block</white>\n\n"
            + "<gray>It <red>can't</red> go in a chest, ender chest, bundle, or hopper — and you <red>can't</red> drop it.</gray>",

        "<bold><#9D4EDD>It Comes Back</#9D4EDD></bold>\n\n"
            + "<gray>If the holder goes <yellow>inactive</yellow>, or the egg sits untouched too long, it <white>respawns at the End</white>.\n\n"
            + "<gold>A countdown warns everyone first</gold> — so race to the End!</gray>",

        "<bold><#9D4EDD>Hunt It Down</#9D4EDD></bold>\n\n"
            + "<gray>Get near a placed egg and a <white>compass arrow</white> shows in your action bar.\n\n"
            + "It runs <green>warmer</green> the closer you get. Even a hidden egg can be found.</gray>",

        "<bold><#9D4EDD>Rewards</#9D4EDD></bold>\n\n"
            + "<gray>Hold the egg and earn <gold>rewards</gold> over time — they grow the longer you keep it.\n\n"
            + "<yellow>You must be actually playing</yellow> — no AFK farming. Lose the egg, lose the streak.</gray>",

        "<bold><gradient:#9D4EDD:#F3ECFF>Dragonlord</gradient></bold>\n\n"
            + "<gray>Hold the egg long enough and you become a <bold><#C9A3FF>Dragonlord</#C9A3FF></bold> — for good.\n\n"
            + "You earn a glowing <light_purple>aura</light_purple> and a title beside your name.</gray>",

        "<bold><#9D4EDD>Your Commands</#9D4EDD></bold>\n\n"
            + "<white>/giveegg <name></white> <gray>- hand it over</gray>\n"
            + "<white>/dr info</white> <gray>- who holds it</gray>\n"
            + "<white>/dr title</white> <gray>- toggle title</gray>\n"
            + "<white>/dr particle</white> <gray>- toggle aura</gray>",

        "<bold><gradient:#9D4EDD:#F3ECFF>Good luck.</gradient></bold>\n\n"
            + "<gray>Claim the egg.\nDefend it.\nBecome the Dragonlord.</gray>\n\n"
            + "<dark_gray>The egg is under watch.</dark_gray>"
    );

    static void give(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.author(Msg.mm("<dark_purple>DragonReign</dark_purple>"));
            meta.title(Msg.mm("<gold>The Dragon Egg</gold>"));
            meta.pages(PAGES.stream().map(Msg::mm).collect(Collectors.toList()));
            book.setItemMeta(meta);
        }
        player.getInventory().addItem(book);
    }
}
