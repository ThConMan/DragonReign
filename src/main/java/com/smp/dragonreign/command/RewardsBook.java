package com.smp.dragonreign.command;

import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.stream.Collectors;

/**
 * Builds the in-game hold-rewards book — a colored written book that explains what
 * holding the egg pays out. Unlike {@link GuideBook} the contents come straight from
 * config (rewards.book.*), so a server can rewrite the title, author, and every page
 * to match whatever reward tiers they've set, with no code change.
 */
final class RewardsBook {

    private RewardsBook() {
    }

    static void give(Player player, ConfigManager config) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.author(Msg.mm(config.getRewardBookAuthor()));
            meta.title(Msg.mm(config.getRewardBookTitle()));
            meta.pages(config.getRewardBookPages().stream().map(Msg::mm).collect(Collectors.toList()));
            book.setItemMeta(meta);
        }
        player.getInventory().addItem(book);
    }
}
