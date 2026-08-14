package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.inbox.Severity;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.store.EggDataStore;
import com.smp.dragonreign.util.Egg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    // Phantom-loss watchdog: armed once we've actually SEEN the keeper carrying the egg,
    // and only trips after several consecutive samples where the egg is nowhere — not on
    // them, not placed, not loose in the world. Guards against any vanish path we haven't
    // met yet (the cursor-close deletion was one) without ever being able to double-give:
    // a placed or loose egg disarms it instantly.
    private boolean carriedSeen;
    private int missingSamples;

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
            carriedSeen = false;
            missingSamples = 0;
            plugin.afk().reset();
            return;
        }
        if (!owner.equals(lastOwner)) {
            // Hand-off: start a fresh window so time never carries across owners.
            lastOwner = owner;
            lastStamp = now;
            carriedSeen = false;
            missingSamples = 0;
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
            missingSamples = 0;
            return;
        }

        boolean carried = Egg.isCarrying(player);
        watchForPhantomLoss(player, carried);

        long delta = now - lastStamp;
        lastStamp = now;
        if (delta <= 0) {
            return;
        }
        if (plugin.afk().isAfk(player)) {
            return; // away — discard this gap
        }

        // Is the keeper genuinely WITH the egg — carrying it, or standing near the placed
        // block? This is what separates an active carrier from a placed-and-abandoned egg,
        // and it drives the staleness refresh below regardless of the reward toggle.
        boolean engaged = carried || nearPlacedEgg(player);

        if (plugin.config().isHoldRequirePresence() && !engaged) {
            return; // egg parked elsewhere — owner isn't actually holding the contest
        }

        // An online, non-AFK keeper who is actually with the egg keeps it "fresh", so the
        // staleness timer (which exists to reclaim abandoned or idle-hoarded eggs) never fires
        // on someone who is simply playing while holding it. Gated on `engaged`, not on the
        // reward presence toggle, so turning require-presence off can't let a keeper refresh
        // staleness from across the map while the egg actually sits parked and unattended.
        if (engaged) {
            plugin.store().touchActivity();
        }

        // Hold REWARDS pay for bearing the egg — the slot it costs and the target it paints
        // on you. A placed egg (safe in a vault, or parked at the End border) pauses the
        // reward clock. Lifetime Dragonlord time keeps the wider rule: standing with your
        // displayed trophy still deepens the bond, it just doesn't pay.
        if (carried || !plugin.config().isRewardCarriedOnly()) {
            plugin.rewards().addActive(owner, delta);
        }
        plugin.victors().addActive(owner, delta);
    }

    /**
     * Is the keeper standing within the configured radius of the placed egg block (in its
     * world)? Together with carrying, this is "genuine engagement" — it closes the
     * "wall the egg into a base and walk away" farm, since parked time doesn't accrue.
     */
    private boolean nearPlacedEgg(Player player) {
        EggLocation loc = plugin.store().getLocation();
        if (loc == null) {
            return false; // not placed — nothing to be near
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

    /**
     * The last line of defence against the egg silently ceasing to exist. We only arm the
     * watchdog after seeing the keeper genuinely carrying the egg; a placed block or a
     * loose item/falling egg anywhere disarms it (those are legitimate not-carried forms
     * that other systems track). If an armed watchdog then sees several consecutive
     * samples with the egg nowhere at all, some path deleted it — hand it back and tell
     * staff, instead of the plugin insisting a vanished egg is still held.
     */
    private void watchForPhantomLoss(Player player, boolean carried) {
        if (carried) {
            carriedSeen = true;
            missingSamples = 0;
            return;
        }
        boolean placed = plugin.store().getLocation() != null;
        boolean loose = plugin.voidGuardian() != null && plugin.voidGuardian().hasLooseEgg();
        if (placed || loose) {
            // The egg exists in a known non-carried form; nothing suspicious.
            carriedSeen = false;
            missingSamples = 0;
            return;
        }
        if (!carriedSeen) {
            return; // never saw them carry it this stint — don't guess
        }
        missingSamples++;
        if (missingSamples < 3) {
            return; // debounce: menus, deaths, and hand-offs all resolve within a sample or two
        }
        carriedSeen = false;
        missingSamples = 0;
        Egg.giveOrDrop(player, 1);
        plugin.store().touchActivity();
        plugin.history().append(EventType.EGG_RECOVERED, player, null,
                "the egg vanished from their hands — restored it");
        plugin.inbox().post(Severity.WARN, "Egg restored to its keeper",
                "The Dragon Egg disappeared from " + player.getName()
                        + "'s inventory without being placed, dropped, or handed over — "
                        + "restored it to them. If this repeats, something is deleting the egg.",
                player.getUniqueId());
        plugin.saveAsync();
    }
}
