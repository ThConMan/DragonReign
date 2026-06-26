package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.store.EggDataStore;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Once per check interval, decide whether the egg's keeper has been gone long enough
 * to warrant bringing the egg back. Cheap: one owner, one comparison.
 *
 * <p>v2 changed two things here without touching the surrounding logic: the last-seen
 * estimate is now <b>group-aware</b> (an IP-linked ring of alts can't keep the clock
 * fresh), and instead of respawning on the spot we hand off to the {@link CountdownManager}
 * so players get a contestable window first.
 */
public final class InactivityTask extends BukkitRunnable {

    private final DragonReign plugin;

    public InactivityTask(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ConfigManager config = plugin.config();
        if (!config.isRespawnEnabled()) {
            return;
        }
        EggDataStore store = plugin.store();
        UUID owner = store.getOwner();
        if (owner == null) {
            // An unowned egg (e.g. just respawned on the fountain) never triggers.
            return;
        }

        long now = System.currentTimeMillis();
        long lastSeen = plugin.ownership().groupLastSeen(owner, now);
        if (lastSeen <= 0) {
            // No record at all — don't treat that as "infinitely inactive".
            lastSeen = now;
        }
        long threshold = TimeUnit.DAYS.toMillis(config.getInactivityDays());

        if (now - lastSeen >= threshold) {
            plugin.getLogger().info("Keeper " + owner + " inactive for >= "
                    + config.getInactivityDays() + " days; requesting egg respawn.");
            // CountdownManager decides between an instant respawn and a timed one, and
            // quietly ignores the request if a countdown is already running.
            plugin.countdown().requestRespawn(owner);
        }
    }
}
