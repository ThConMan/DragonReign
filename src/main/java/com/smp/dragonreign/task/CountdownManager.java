package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.inbox.Severity;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Players;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the single, in-memory respawn countdown. When the inactivity check decides the
 * egg should come back, it no longer respawns instantly — it asks here for a countdown
 * so players have a window to gather at the End and contest the egg.
 *
 * <p>Only one countdown runs at a time; the minute-scale inactivity task can call
 * {@link #requestRespawn(UUID)} repeatedly and we just ignore the extras. The countdown
 * is intentionally NOT persisted: a restart drops it, and the inactivity task simply
 * re-triggers a fresh one on its next tick. Nothing to corrupt on disk.
 */
public final class CountdownManager {

    private final DragonReign plugin;
    private final RespawnSequence respawn;

    private boolean active;
    private boolean firing;          // guards against the egg respawning twice (0-tick vs. force race)
    private UUID oldOwner;
    private long endEpoch;
    private BukkitTask ticker;
    private final Set<Integer> warned = new HashSet<>();

    public CountdownManager(DragonReign plugin) {
        this.plugin = plugin;
        this.respawn = new RespawnSequence(plugin);
    }

    // ── Entry points ───────────────────────────────────────────────────────────

    /**
     * The inactivity task's request to bring the egg back. Starts a countdown (or, if
     * countdowns are disabled, respawns immediately — preserving the original v1 feel).
     *
     * @return {@code true} if a countdown was started (or an instant respawn fired);
     *         {@code false} if the request was ignored because one is already running
     *         or there is no owner to respawn.
     */
    public boolean requestRespawn(UUID owner) {
        if (active || owner == null) {
            return false; // already counting down, or nothing to respawn
        }
        ConfigManager c = plugin.config();
        if (!c.isCountdownEnabled()) {
            respawn.run(owner);
            return true;
        }

        active = true;
        firing = false;
        oldOwner = owner;
        warned.clear();
        int duration = c.getCountdownDurationSeconds();
        endEpoch = System.currentTimeMillis() + duration * 1000L;
        // The "started" broadcast covers the opening beat, so suppress a warn mark that
        // happens to equal the full duration — otherwise players get two near-identical
        // lines (started + warn) plus a title and sound in the same tick.
        warned.add(duration);

        plugin.history().appendSystem(EventType.RESPAWN_COUNTDOWN_STARTED,
                plugin.store().getLocation(),
                "respawn countdown started (" + duration + "s); keeper " + Players.name(owner));
        plugin.inbox().post(Severity.WARN, "Respawn countdown started",
                "Keeper " + Players.name(owner) + " is inactive — egg respawns in " + duration + "s unless they return.",
                owner);
        plugin.announce().broadcastCountdownStarted(duration);

        ticker = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L); // every second

        plugin.saveAsync();
        return true;
    }

    private void tick() {
        if (!active) {
            return;
        }
        long remainingMillis = endEpoch - System.currentTimeMillis();
        int secondsLeft = (int) Math.ceil(remainingMillis / 1000.0);
        if (secondsLeft <= 0) {
            fire();
            return;
        }
        maybeWarn(secondsLeft);
    }

    private void maybeWarn(int secondsLeft) {
        // Fire every not-yet-warned mark at or above the current value, so a lag spike
        // that skips a scheduler tick (e.g. 5 -> 3) can't silently drop the mark at 4 or
        // the final "1s" call. Collapse any caught-up marks into a single broadcast at
        // the real remaining time rather than spamming one line per skipped mark.
        boolean fire = false;
        for (int mark : plugin.config().getCountdownWarnSeconds()) {
            if (mark >= secondsLeft && warned.add(mark)) {
                fire = true;
            }
        }
        if (fire) {
            plugin.announce().broadcastCountdownWarn(secondsLeft);
        }
    }

    /** The countdown reached zero (or was forced): do the actual respawn, exactly once. */
    private void fire() {
        if (firing) {
            return;
        }
        firing = true;
        UUID owner = oldOwner;
        stopTicker();
        clearState();
        respawn.run(owner); // posts its own EGG_ERASED/RESPAWN_TRIGGERED + inbox entries
    }

    /**
     * The current keeper (or, when IP-alt checks are on, one of their linked alts) logged
     * back in. If aborting is enabled, cancel the countdown — the egg stays put.
     */
    public void onOwnerReturn(UUID uuid) {
        if (!active || uuid == null) {
            return;
        }
        ConfigManager c = plugin.config();
        if (!c.isAbortOnOwnerReturn()) {
            return;
        }
        boolean isKeeper = uuid.equals(oldOwner);
        if (!isKeeper && c.isCheckIpAlts() && oldOwner != null) {
            // DIRECT IP link only — not transitive group membership. A transitive group
            // can balloon on CGNAT/VPN/shared-LAN hashes, letting an unrelated stranger
            // who merely shares an exit node abort the contest. A direct shared-IP link
            // with the actual keeper is a far better "this is probably their alt" signal.
            isKeeper = plugin.ipRegistry().sharesIp(uuid, oldOwner);
        }
        if (!isKeeper) {
            return; // an unrelated player returning must not save someone else's egg
        }

        String name = Players.name(uuid);
        stopTicker();
        UUID keeper = oldOwner;
        clearState();

        plugin.history().appendSystem(EventType.RESPAWN_COUNTDOWN_ABORTED,
                plugin.store().getLocation(),
                "keeper " + name + " returned — countdown aborted, egg stays");
        plugin.inbox().post(Severity.INFO, "Countdown aborted",
                "Keeper " + name + " returned before the timer expired; the egg stays put.", keeper);
        plugin.announce().broadcastKeeperReturned(name);
        plugin.store().touchActivity(); // they're back; reset the inactivity clock
        plugin.saveAsync();
    }

    // ── Admin commands ──────────────────────────────────────────────────────────

    public void cancelByAdmin(CommandSender sender) {
        ConfigManager c = plugin.config();
        if (!active) {
            sender.sendMessage(Msg.prefixed(c.getPrefix(), "<yellow>No respawn countdown is active.</yellow>"));
            return;
        }
        UUID keeper = oldOwner;
        stopTicker();
        clearState();
        plugin.history().append(EventType.RESPAWN_COUNTDOWN_ABORTED, "ADMIN", null,
                plugin.store().getLocation(), "countdown cancelled by " + sender.getName());
        plugin.inbox().post(Severity.INFO, "Countdown cancelled",
                sender.getName() + " cancelled the respawn countdown.", keeper);
        sender.sendMessage(Msg.prefixed(c.getPrefix(), "<green>Respawn countdown cancelled — the egg stays.</green>"));
        plugin.saveAsync();
    }

    /** Skip the countdown and respawn right now. Uses the current tracked owner. */
    public void forceNow(CommandSender sender) {
        ConfigManager c = plugin.config();
        UUID owner = active ? oldOwner : plugin.store().getOwner();
        if (owner == null) {
            sender.sendMessage(Msg.prefixed(c.getPrefix(),
                    "<red>The egg is unowned — there's nothing to respawn.</red>"));
            return;
        }
        if (active) {
            // Reuse the firing guard so a natural 0-tick can't double up with this.
            oldOwner = owner;
            fire();
        } else {
            respawn.run(owner);
        }
        plugin.history().append(EventType.ADMIN, sender.getName(), null,
                plugin.store().getLocation(), "forced an immediate egg respawn");
        sender.sendMessage(Msg.prefixed(c.getPrefix(), "<green>Egg respawn forced.</green>"));
    }

    // ── Status (for /dragonreign info) ──────────────────────────────────────────

    public boolean isActive() {
        return active;
    }

    public int secondsLeft() {
        if (!active) {
            return 0;
        }
        return Math.max(0, (int) Math.ceil((endEpoch - System.currentTimeMillis()) / 1000.0));
    }

    /** Human-readable status line for the info command. */
    public String statusText() {
        if (!active) {
            return "idle";
        }
        return secondsLeft() + "s left, keeper " + Players.name(oldOwner);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    /** Stop the scheduler without logging an abort — used on plugin disable. */
    public void shutdown() {
        stopTicker();
        clearState();
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void clearState() {
        active = false;
        oldOwner = null;
        endEpoch = 0L;
        warned.clear();
    }
}
