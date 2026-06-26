package com.smp.dragonreign.listener;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.inbox.Severity;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.store.EggDataStore;
import com.smp.dragonreign.util.Egg;
import com.smp.dragonreign.util.Msg;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Read-only bookkeeping at MONITOR priority — it reacts to outcomes the protection
 * listeners already let through, keeping owner/location/timestamps current.
 */
public final class EggTrackingListener implements Listener {

    private final DragonReign plugin;

    public EggTrackingListener(DragonReign plugin) {
        this.plugin = plugin;
    }

    private EggDataStore store() {
        return plugin.store();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!Egg.isDragonEgg(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        EggLocation loc = EggLocation.of(block);
        store().setOwner(player.getUniqueId(), "placed");
        store().setLocation(loc);
        store().touchActivity();
        plugin.history().append(EventType.PLACED, player, loc, "placed the egg");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!Egg.isDragonEgg(block.getType())) {
            return;
        }
        EggLocation loc = EggLocation.of(block);
        // It's about to become a dropped item / teleport; a pickup event will reassign
        // ownership. We just note it left the placed state.
        store().clearLocation();
        store().touchActivity();
        plugin.history().append(EventType.BROKEN, event.getPlayer(), loc, "broke the egg block");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent event) {
        // The egg teleports when struck and can land as a falling block.
        if (event.getEntity() instanceof FallingBlock && event.getTo() == Material.DRAGON_EGG) {
            EggLocation loc = EggLocation.of(event.getBlock());
            store().setLocation(loc);
            store().touchActivity();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!Egg.isDragonEgg(event.getItem().getItemStack())) {
            return;
        }
        store().setOwner(player.getUniqueId(), "picked up");
        store().clearLocation();
        store().touchActivity();
        plugin.history().append(EventType.PICKED_UP, player, null, "picked up the egg");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        markSeenIfOwner(player.getUniqueId());

        // IP recording is ALWAYS on (independent of the strict-ownership toggle) so the
        // login history already exists the day an admin decides to flip strict mode on.
        // Only a salted hash is stored — never the raw address. See IpRegistry.
        plugin.ipRegistry().recordLogin(player, plugin.config().getIpHashSalt());

        // If this player is the keeper a respawn countdown is waiting on, their return
        // (optionally) aborts it — they kept the egg by showing up.
        plugin.countdown().onOwnerReturn(player.getUniqueId());

        // Crash recovery: egg(s) pulled from this player's death drops that were never
        // handed back (server stopped before they respawned). Only honoured while the egg
        // is still theirs to hold — a respawn flushes pending give-backs, so an owned egg
        // here means no respawn intervened. Done before the erase sweep below.
        int owed = store().consumePendingGive(player.getUniqueId());
        if (owed > 0) {
            Egg.giveOrDrop(player, owed);
            plugin.history().append(EventType.BLOCKED_DROP, player, null,
                    "returned " + owed + " death-held egg(s) on join (crash recovery)");
        }

        // Erase sweep on join: a player is cleaned if they were explicitly flagged
        // (pending-erase), OR the tracked egg is currently unowned — i.e. it lives on the
        // fountain — so ANY egg they bring in is by definition a stray that must not
        // coexist with the single spawned egg (covers offline holders we never flagged).
        boolean flagged = store().consumePendingErase(player.getUniqueId());
        boolean eggIsUnowned = owed <= 0 && store().getOwner() == null;
        if (flagged || eggIsUnowned) {
            int removed = Egg.purgeFrom(player.getInventory());
            removed += Egg.purgeFrom(player.getEnderChest());
            if (removed > 0) {
                String why = flagged ? "pending erase" : "egg is unowned (on the fountain)";
                plugin.history().append(EventType.EGG_ERASED, player, null,
                        "stripped " + removed + " stale egg(s) on join (" + why + ")");
                plugin.inbox().post(Severity.INFO, "Stale egg cleanup",
                        "Stripped " + removed + " stale egg(s) from " + player.getName()
                                + " on join (" + why + ").", player.getUniqueId());
            }
        }

        // Staff join notice: let admins know there are unread alerts waiting.
        if (plugin.config().isInboxNotifyOnJoin()
                && player.hasPermission("dragonreign.admin")) {
            int unread = plugin.inbox().unreadCount();
            if (unread > 0) {
                player.sendMessage(Msg.prefixed(plugin.config().getPrefix(),
                        "<gold>" + unread + " unread alert" + (unread == 1 ? "" : "s")
                                + "</gold> <gray>→</gray> <yellow>/dragonreign inbox</yellow>"));
            }
        }

        // Persist the refreshed login record (and any cleanup) off-thread.
        plugin.saveAsync();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        markSeenIfOwner(event.getPlayer().getUniqueId());
    }

    /**
     * Only the current keeper's last-seen matters (it's the single value the
     * inactivity check reads). Recording every player who ever joins would grow
     * data.yml without bound for no benefit.
     */
    private void markSeenIfOwner(java.util.UUID uuid) {
        if (uuid != null && uuid.equals(store().getOwner())) {
            store().markSeen(uuid);
        }
    }
}
