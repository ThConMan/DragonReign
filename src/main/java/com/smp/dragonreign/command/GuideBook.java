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

    // MiniMessage pages. Book pages are cream parchment, so everything here uses DARK,
    // high-contrast colours (black body, dark-purple accents, dark-red warnings, dark-green
    // positives) — light colours like white/gray/yellow are unreadable on a book.
    private static final List<String> PAGES = List.of(
        "<bold><gradient:#3D1466:#7B2FB5>The Dragon Egg</gradient></bold>\n\n"
            + "<black>One egg. The whole server wants it.\n\n"
            + "This little book explains how it works — and how to claim it.</black>\n\n"
            + "<dark_gray>» turn the page «</dark_gray>",

        "<bold><dark_purple>The Rules</dark_purple></bold>\n\n"
            + "<black>The egg can't be locked away — it <dark_red>won't go into</dark_red> a chest, ender chest, bundle, hopper, or item frame.</black>\n\n"
            + "<black>Carry it, place it, or drop it — it always stays <dark_purple>out in the open</dark_purple> where someone can grab it.</black>",

        "<bold><dark_purple>It Comes Back</dark_purple></bold>\n\n"
            + "<black>If the holder goes <dark_red>inactive</dark_red>, or the egg sits untouched too long, it <dark_purple>respawns at the End</dark_purple>.\n\n"
            + "<dark_red>A countdown warns everyone first</dark_red> — so race to the End!</black>",

        "<bold><dark_purple>Hunt It Down</dark_purple></bold>\n\n"
            + "<black>Get near a placed egg and a <dark_purple>compass arrow</dark_purple> shows in your action bar.\n\n"
            + "It runs <dark_green>warmer</dark_green> the closer you get. Even a hidden egg can be found.</black>",

        "<bold><dark_purple>Rewards</dark_purple></bold>\n\n"
            + "<black>Hold the egg and earn <dark_green>rewards</dark_green> over time — they grow the longer you keep it.\n\n"
            + "<dark_red>You must be actually playing</dark_red> — no AFK farming. Lose the egg, lose the streak.</black>",

        "<bold><gradient:#3D1466:#7B2FB5>Dragonlord</gradient></bold>\n\n"
            + "<black>Hold the egg long enough and you become a <bold><dark_purple>Dragonlord</dark_purple></bold> — for good.\n\n"
            + "You earn a glowing <dark_purple>aura</dark_purple> and a title beside your name.</black>",

        "<bold><dark_purple>Your Commands</dark_purple></bold>\n\n"
            + "<dark_purple>/giveegg <name></dark_purple> <black>- hand it over</black>\n"
            + "<dark_purple>/dr info</dark_purple> <black>- who holds it</black>\n"
            + "<dark_purple>/dr title</dark_purple> <black>- toggle title</black>\n"
            + "<dark_purple>/dr particle</dark_purple> <black>- toggle aura</black>",

        "<bold><gradient:#3D1466:#7B2FB5>Good luck.</gradient></bold>\n\n"
            + "<black>Claim the egg.\nDefend it.\nBecome the Dragonlord.</black>\n\n"
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
