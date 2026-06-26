package com.smp.dragonreign.listener;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.util.Egg;
import com.smp.dragonreign.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;

/** Feature 2 — you simply can't drop the Dragon Egg (manually or on death). */
public final class DropProtectionListener implements Listener {

    private final DragonReign plugin;

    public DropProtectionListener(DragonReign plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ConfigManager config = plugin.config();
        if (!config.isNoDrop()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("dragonreign.bypass")) {
            return;
        }
        if (!Egg.isDragonEgg(event.getItemDrop().getItemStack())) {
            return;
        }

        event.setCancelled(true);
        Msg.nudge(player, Msg.prefixed(config.getPrefix(), config.getBlockedDropMessage()));

        if (config.isLogBlocksToHistory()
                && !Msg.throttled("drop:" + player.getUniqueId(), 2000L)) {
            plugin.history().append(EventType.BLOCKED_DROP, player, null, "tried to drop the egg");
        }
    }

    /**
     * Death is the other way the egg reaches the ground (keepInventory off). Pull any
     * egg — including eggs nested in dropped bundles — out of the death drops and hand
     * it back on respawn, so the single special egg never despawns, burns, or gets
     * hoovered into a chest by a hopper.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.config().isNoDrop()) {
            return;
        }
        Player player = event.getEntity();
        if (player.hasPermission("dragonreign.bypass")) {
            return;
        }

        int saved = 0;
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (Egg.isDragonEgg(stack)) {
                saved += stack.getAmount();
                it.remove();
            } else if (Egg.isBundle(stack) && Egg.bundleContainsDragonEgg(stack)) {
                saved += Egg.cleanBundle(stack); // strips nested eggs, leaves the bundle to drop
            }
        }

        if (saved > 0) {
            // Persisted (not an in-memory map) so a crash/restart between death and
            // respawn can't lose the unique egg — it's honoured again on next join.
            plugin.store().addPendingGive(player.getUniqueId(), saved);
            plugin.history().append(EventType.BLOCKED_DROP, player, null,
                    "kept " + saved + " egg(s) out of death drops");
            plugin.saveAsync();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // If the egg respawned on the fountain while this player sat on the death screen,
        // RespawnSequence already flushed every pending give-back, so this is 0 and the
        // player correctly gets nothing (no dupe). Otherwise, hand their egg(s) back.
        int count = plugin.store().consumePendingGive(player.getUniqueId());
        if (count <= 0) {
            return;
        }
        Egg.giveOrDrop(player, count);
        plugin.saveAsync();
    }
}
