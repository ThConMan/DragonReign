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
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
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
            if (plugin.config().isEggFireproof()) {
                // Immune to fire, lava, cactus and explosions — the one egg can't be destroyed.
                // (The void still removes it by position below, so a void drop still recovers.)
                item.setInvulnerable(true);
            }
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

    /** Is a loose egg (dropped item or falling block) known to exist right now? */
    public boolean hasLooseEgg() {
        return tracked != null && tracked.isValid() && !tracked.isDead();
    }

    // ── The egg simply cannot be destroyed ──────────────────────────────────────

    /**
     * The invulnerable flag set in {@link #onItemSpawn} should already shrug off TNT and
     * creepers, but pressure testing proved a loose egg still dies to explosions. So the
     * guarantee lives here instead, where no vanilla damage bookkeeping can dodge it:
     * every damage event against a dragon-egg item is flatly cancelled. The void isn't
     * damage — a void drop still recovers through {@link #tick()} as before.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!plugin.config().isEggFireproof()) {
            return;
        }
        if (event.getEntity() instanceof Item item && Egg.isDragonEgg(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * A creeper or TNT blast that catches the PLACED egg block would destroy it outright
     * most of the time (explosions only sometimes drop the block). Pull the egg out of the
     * blast's block list so the explosion happens around it — the one egg is not allowed
     * to be collateral damage. If the blast removes what it stood on, gravity turns it
     * into a falling block and the tracking above takes over.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (plugin.config().isEggFireproof()) {
            event.blockList().removeIf(b -> b.getType() == Material.DRAGON_EGG);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (plugin.config().isEggFireproof()) {
            event.blockList().removeIf(b -> b.getType() == Material.DRAGON_EGG);
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
        // The egg refuses to stay in lava or water — buoy it back toward the surface. Combined
        // with its fire-immunity, a loose egg that lands in lava floats out instead of burning.
        if (tracked instanceof Item) {
            Material in = tracked.getLocation().getBlock().getType();
            if (in == Material.LAVA || in == Material.WATER) {
                org.bukkit.util.Vector v = tracked.getVelocity();
                tracked.setVelocity(new org.bukkit.util.Vector(v.getX() * 0.4,
                        Math.max(v.getY(), 0.28), v.getZ() * 0.4));
            }
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
        // The egg is owed back to someone mid-death. It exists — as a ledger entry rather
        // than a block or an item — and recovering a "lost" copy now would mean two eggs the
        // moment that player respawns.
        if (plugin.store().hasAnyPendingGive()) {
            return true;
        }
        UUID owner = plugin.store().getOwner();
        if (owner != null) {
            Player p = Bukkit.getPlayer(owner);
            // Covers offhand, armour slots, cursor and bundles — a bare contains() check on
            // the main inventory misses the egg mid-click and reports the real egg as gone.
            if (p != null && Egg.isCarrying(p)) {
                return true;
            }
        }
        return false;
    }
}
