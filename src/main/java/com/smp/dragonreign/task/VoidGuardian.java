package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.util.Egg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

/**
 * Makes sure the single egg can never be permanently lost when it's loose in the world —
 * a dropped item or a falling block (the dragon egg has gravity). It tracks only that one
 * loose entity (driven by spawn events, never world scans) and, on a light timer, checks
 * just that entity's height. If it falls into the void the egg is rebuilt at the End.
 *
 * <p>The placed-block form is already tracked by the data store, so this only watches the
 * loose forms. There is one egg, so there is at most one tracked entity at a time.
 */
public final class VoidGuardian implements Listener {

    // Trigger recovery a few blocks ABOVE the world floor rather than strictly below it, so
    // a fast-falling egg is caught before it can cross the void-kill plane between checks.
    // Kept small so it stays clearly within the void (no blocks exist below the floor).
    private static final int DANGER_BUFFER = 4;

    private final DragonReign plugin;
    private final RespawnSequence respawn;

    private Entity tracked;

    public VoidGuardian(DragonReign plugin, RespawnSequence respawn) {
        this.plugin = plugin;
        this.respawn = respawn;
    }

    // ── Tracking the loose egg ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.config().isVoidSafetyEnabled()) {
            return;
        }
        Item item = event.getEntity();
        if (Egg.isDragonEgg(item.getItemStack())) {
            item.setUnlimitedLifetime(true); // the unique egg must never despawn on the ground
            tracked = item;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!plugin.config().isVoidSafetyEnabled()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock fb && fb.getBlockData().getMaterial() == Material.DRAGON_EGG) {
            tracked = fb;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLand(EntityChangeBlockEvent event) {
        // The falling block became a placed block again — it's no longer loose.
        if (tracked != null && tracked.equals(event.getEntity())) {
            tracked = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        // Someone grabbed the dropped egg — it's safe in an inventory now.
        if (tracked != null && tracked.equals(event.getItem())) {
            tracked = null;
        }
    }

    // ── Light timer ──────────────────────────────────────────────────────────────

    /** Scheduled every void-safety.check-ticks. Only the one tracked entity is examined. */
    public void tick() {
        if (tracked == null || !plugin.config().isVoidSafetyEnabled()) {
            return;
        }
        if (!tracked.isValid() || tracked.isDead()) {
            tracked = null;
            return;
        }
        World world = tracked.getWorld();
        if (tracked.getLocation().getY() < world.getMinHeight() + DANGER_BUFFER) {
            // Falling into the void. Only recover if the one canonical egg is genuinely
            // gone — otherwise this loose entity is a stray duplicate (e.g. an admin /give
            // or a datapack drop), and minting a fresh canonical egg would create a second.
            Entity lost = tracked;
            tracked = null;
            lost.remove();
            if (canonicalEggExists()) {
                plugin.getLogger().warning("A loose dragon egg fell into the void in "
                        + world.getName() + " while the tracked egg still exists — removed "
                        + "it as a stray duplicate instead of recovering.");
                return;
            }
            respawn.recoverToPortal("dropped/fell out of " + world.getName());
        }
    }

    /**
     * Is the one tracked (canonical) egg still present somewhere safe — as the placed block,
     * or carried by its online owner? If so, a loose egg falling into the void is a stray
     * copy, not the real egg, and must not trigger a recovery (which would duplicate it).
     */
    private boolean canonicalEggExists() {
        EggLocation loc = plugin.store().getLocation();
        if (loc != null) {
            Optional<Location> at = loc.toBukkit();
            if (at.isPresent() && at.get().getBlock().getType() == Material.DRAGON_EGG) {
                return true;
            }
        }
        UUID owner = plugin.store().getOwner();
        if (owner != null) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null && p.getInventory().contains(Material.DRAGON_EGG)) {
                return true;
            }
        }
        return false;
    }
}
