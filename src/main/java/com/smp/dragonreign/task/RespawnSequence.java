package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.announce.AnnouncementService;
import com.smp.dragonreign.inbox.Severity;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.store.EggDataStore;
import com.smp.dragonreign.util.Egg;
import com.smp.dragonreign.util.EndPortalEggSpawner;
import com.smp.dragonreign.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * The four-step respawn the inactivity task triggers: erase the old egg, spawn a
 * fresh one on the End fountain, announce it, and reset tracking. Runs entirely in
 * one main-thread tick.
 */
public final class RespawnSequence {

    private final DragonReign plugin;

    public RespawnSequence(DragonReign plugin) {
        this.plugin = plugin;
    }

    public void run(java.util.UUID oldOwner) {
        EggDataStore store = plugin.store();
        EndPortalEggSpawner spawner = plugin.spawner();
        AnnouncementService announce = plugin.announce();

        String oldOwnerName = Players.name(oldOwner);

        // 1) Spawn the fresh egg FIRST. If the End isn't available we must not erase
        //    the existing egg — otherwise the single special egg is lost forever with
        //    no owner left to re-trigger. Defer and retry on the next interval instead.
        Optional<EggLocation> spawned = spawner.spawnAtExitPortal();
        if (spawned.isEmpty()) {
            plugin.getLogger().warning("Respawn deferred: End world unavailable; "
                    + "leaving the current egg in place and retrying next interval.");
            plugin.history().appendSystem(EventType.RESPAWN_TRIGGERED, store.getLocation(),
                    "deferred — End world unavailable (keeper " + oldOwnerName + " stays until it loads)");
            return;
        }
        plugin.history().appendSystem(EventType.EGG_SPAWNED, spawned.get(), "fresh egg on the End fountain");

        // 2) Erase the tracked egg block — UNLESS it's the very block the spawner just
        //    (re)used. spawnAtExitPortal is idempotent: if the tracked egg already sat on
        //    the fountain apex it placed nothing and reported that same block. Erasing it
        //    here would AIR-out the only egg, leaving zero. So skip when they coincide.
        EggLocation tracked = store.getLocation();
        if (tracked != null && tracked.sameBlock(spawned.get())) {
            plugin.history().appendSystem(EventType.EGG_SPAWNED, spawned.get(),
                    "tracked egg already on the fountain apex — reused, not re-placed");
        } else {
            eraseTrackedBlock(tracked);
        }

        // 3) Purge any stray copy. The egg is meant to be uncontainable, so the only
        //    legitimate homes are the tracked block (now erased) or a player's own
        //    inventory/ender chest. Sweep EVERY online player — not just the old owner
        //    — so a stashed/handed-off egg can't coexist with the freshly spawned one.
        //    Track the keeper's strips separately so we don't false-alarm on the single
        //    egg they're legitimately expected to be holding.
        int keeperStripped = 0;
        int otherStripped = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            int got = Egg.purgeFrom(online.getInventory()) + Egg.purgeFrom(online.getEnderChest());
            if (oldOwner != null && oldOwner.equals(online.getUniqueId())) {
                keeperStripped += got;
            } else {
                otherStripped += got;
            }
        }

        // Death-held give-backs are now void (the egg respawned). Flush them so a keeper
        // sitting on the death screen can't be handed a SECOND egg on respawn. Split the
        // keeper's own copy out of the stray accounting, same as the inventory sweep.
        int keeperGive = oldOwner != null ? store.consumePendingGive(oldOwner) : 0;
        int otherGive = store.clearAllPendingGive();

        int totalStripped = keeperStripped + otherStripped + keeperGive + otherGive;

        // Flag every account that has ever held the egg for erase-on-join, so a stray
        // copy held by an OFFLINE non-owner can't coexist with the spawned egg until the
        // next time they're swept. Online players were just cleaned, so clear their flag.
        for (UUID holder : store.knownHolders()) {
            store.addPendingErase(holder);
        }
        store.addPendingErase(oldOwner);
        for (Player online : Bukkit.getOnlinePlayers()) {
            store.consumePendingErase(online.getUniqueId());
        }

        Player onlineOwner = oldOwner != null ? Bukkit.getPlayer(oldOwner) : null;
        if (onlineOwner != null) {
            plugin.history().append(EventType.EGG_ERASED, onlineOwner, null,
                    "owner online — stripped " + totalStripped + " egg(s) across online players");
        } else {
            plugin.history().appendSystem(EventType.EGG_ERASED, null,
                    "stripped " + totalStripped + " egg(s) online; flagged offline holders for erase on next join");
        }

        // Surface only GENUINE strays to staff: eggs from players other than the keeper,
        // plus anything the keeper held BEYOND the single copy they're expected to have.
        // A normal respawn with the keeper online holding exactly one egg is NOT a dupe.
        int keeperHeld = keeperStripped + keeperGive;
        int stray = otherStripped + otherGive + Math.max(0, keeperHeld - 1);
        if (stray > 0) {
            plugin.inbox().post(Severity.WARN, "Stray egg cleanup",
                    "Respawn cleaned up " + stray + " unexpected egg copy(ies) (previous keeper "
                            + oldOwnerName + "). A duplicate egg was floating around.", oldOwner);
        }

        // 4) Announce.
        announce.broadcastRespawn(spawned.get());

        // 5) Reset tracking. Owner is null afterwards so it won't refire until reclaimed.
        store.setOwner(null, "respawn reset");
        store.setLocation(spawned.get());
        store.touchActivity();
        plugin.history().appendSystem(EventType.RESPAWN_TRIGGERED, spawned.get(),
                "previous keeper: " + oldOwnerName);
        plugin.inbox().post(Severity.CRITICAL, "Egg respawned",
                "The Dragon Egg respawned on the End fountain. Previous keeper: " + oldOwnerName + ".", oldOwner);

        plugin.saveAsync();
    }

    /**
     * Bring the egg back to the End fountain after it was lost in a loose form (fell into
     * the void). Unlike {@link #run}, this skips the "keeper vanished" fanfare and the
     * stray-egg sweep — neither fits a void rescue. The caller has already removed the
     * lost entity. Returns false (and leaves tracking untouched) if the End is unavailable.
     */
    public boolean recoverToPortal(String reason) {
        EggDataStore store = plugin.store();
        Optional<EggLocation> spawned = plugin.spawner().spawnAtExitPortal();
        if (spawned.isEmpty()) {
            plugin.getLogger().warning("Void recovery deferred: End world unavailable.");
            return false;
        }
        plugin.history().appendSystem(EventType.EGG_SPAWNED, spawned.get(),
                "fresh egg on the End fountain (void recovery)");

        store.setOwner(null, "void recovery");
        store.setLocation(spawned.get());
        store.touchActivity();

        plugin.history().appendSystem(EventType.EGG_RECOVERED, spawned.get(),
                "the egg fell into the void and was returned to the End (" + reason + ")");
        plugin.inbox().post(Severity.CRITICAL, "Egg recovered from the void",
                "The Dragon Egg fell into the void and was returned to the End fountain ("
                        + reason + ").", (UUID[]) null);
        plugin.saveAsync();
        return true;
    }

    private void eraseTrackedBlock(EggLocation loc) {
        if (loc == null) {
            return;
        }
        Optional<Location> bukkit = loc.toBukkit();
        if (bukkit.isEmpty()) {
            return;
        }
        Block block = bukkit.get().getBlock();
        if (block.getType() == Material.DRAGON_EGG) {
            block.setType(Material.AIR, false);
        }
    }
}
