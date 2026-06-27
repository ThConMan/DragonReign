package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.store.EggDataStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;
import java.util.UUID;

/**
 * The one place "active held time" is measured. Once per accrual tick it works out how
 * much real time the current keeper has been online and not away since the last tick,
 * then feeds that delta to both the hold rewards and the victor totals.
 *
 * <p>Using a wall-clock delta (rather than counting ticks) means a lag spike or a longer
 * configured interval can never over- or under-count. Time is discarded whenever the egg
 * is unowned, the keeper is offline, the keeper is away, the egg just changed hands, or
 * (when require-presence is on) the keeper has parked the egg away from themselves.
 */
public final class HoldTimeTask extends BukkitRunnable {

    private final DragonReign plugin;

    private long lastStamp;
    private UUID lastOwner;

    public HoldTimeTask(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        EggDataStore store = plugin.store();
        UUID owner = store.getOwner();
        long now = System.currentTimeMillis();

        if (owner == null) {
            // Unowned egg (e.g. sitting on the fountain) accrues nothing.
            lastOwner = null;
            plugin.afk().reset();
            return;
        }
        if (!owner.equals(lastOwner)) {
            // Hand-off: start a fresh window so time never carries across owners.
            lastOwner = owner;
            lastStamp = now;
            plugin.afk().reset();
            // A new keeper clears any standing manual revoke for them, so a past revoke
            // doesn't permanently block a future, legitimately-earned promotion.
            plugin.victors().clearSuppression(owner);
            return;
        }

        Player player = Bukkit.getPlayer(owner);
        if (player == null) {
            // Offline time is never active time; advance the stamp so it isn't credited later.
            lastStamp = now;
            return;
        }

        long delta = now - lastStamp;
        lastStamp = now;
        if (delta <= 0) {
            return;
        }
        if (plugin.afk().isAfk(player)) {
            return; // away — discard this gap
        }
        if (plugin.config().isHoldRequirePresence() && !isEngaged(player)) {
            return; // egg parked elsewhere — owner isn't actually holding the contest
        }

        plugin.rewards().addActive(owner, delta);
        plugin.victors().addActive(owner, delta);
    }

    /**
     * Genuine engagement with the egg: either carrying it in inventory, or standing within
     * the configured radius of the placed egg block (in its world). This closes the
     * "wall the egg into a base and walk away" farm — parked time no longer accrues.
     */
    private boolean isEngaged(Player player) {
        if (player.getInventory().contains(Material.DRAGON_EGG)) {
            return true; // carrying the egg always counts
        }
        EggLocation loc = plugin.store().getLocation();
        if (loc == null) {
            return false; // not placed and not carried — nothing to be near
        }
        Optional<Location> at = loc.toBukkit();
        if (at.isEmpty()) {
            return false; // egg's world isn't loaded
        }
        Location egg = at.get();
        if (egg.getWorld() == null || !egg.getWorld().equals(player.getWorld())) {
            return false;
        }
        double r = plugin.config().getHoldPresenceRadius();
        return egg.distanceSquared(player.getLocation()) <= r * r;
    }
}
