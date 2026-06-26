package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.util.Egg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Feature 3 — a safety net for any path we miss (or eggs squirreled away before the
 * plugin was installed). Pulls dragon eggs out of online players' ender chests and
 * hands them back.
 */
public final class EnderChestSweepTask extends BukkitRunnable {

    private final DragonReign plugin;

    public EnderChestSweepTask(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ConfigManager config = plugin.config();
        if (!config.isEnderSweepEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Respect the bypass permission, consistent with the container/drop guards:
            // an admin staging the egg in their ender chest shouldn't have it yanked.
            if (player.hasPermission("dragonreign.bypass")) {
                continue;
            }
            int recovered = Egg.purgeFrom(player.getEnderChest());
            if (recovered <= 0) {
                continue;
            }
            // Return the egg(s): straight to the inventory, or at the player's feet if full.
            Egg.giveOrDrop(player, recovered);
            plugin.history().append(EventType.ENDERCHEST_RETURN, player, null,
                    "recovered " + recovered + " egg(s) from ender chest");
        }
    }
}
