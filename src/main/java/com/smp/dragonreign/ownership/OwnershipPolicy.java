package com.smp.dragonreign.ownership;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.inbox.Severity;
import com.smp.dragonreign.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The brain of strict-ownership. Two jobs:
 *
 * <ol>
 *   <li><b>Group-aware last-seen</b> — the always-on, low-false-positive half. The
 *       inactivity clock asks "when did this keeper last show up?", and when IP-alt
 *       checking is on we answer with the most recent login across the keeper's whole
 *       IP-linked group. That way a ring of alts on one connection can't keep the egg
 *       alive while the actual person is gone.</li>
 *   <li><b>Transfer evaluation</b> — when the egg changes hands and strict mode is on,
 *       decide whether the new keeper looks like a genuine independent player or like
 *       the same person's alt / an inactive account, and flag it to the staff inbox.</li>
 * </ol>
 *
 * <p>IP matching is imperfect (shared houses, CGNAT, mobile carriers, VPNs), so the
 * default posture is <b>flag, don't enforce</b>. Enforcement is opt-in.
 */
public final class OwnershipPolicy {

    private final DragonReign plugin;

    // In-memory juggle tracker: the most recent transfer's (previous, receiver, when).
    // Used to spot the egg being volleyed back and forth between the same two accounts —
    // a hallmark of two-account alt-juggling that off-network IP checks can't see.
    private UUID lastTransferPrev;
    private UUID lastTransferRecv;
    private long lastTransferAt;

    public OwnershipPolicy(DragonReign plugin) {
        this.plugin = plugin;
    }

    // ── Group-aware last-seen ──────────────────────────────────────────────────

    /**
     * Best last-seen estimate for the keeper. When {@code check-ip-alts} is on this is
     * the max across the keeper's IP-linked group (capped so a CGNAT/VPN endpoint shared
     * by hordes of strangers can't be used to keep the egg immortal); otherwise it's just
     * the keeper. An {@code auto-enforce-ip-links} floor, if set, pins the result no
     * newer than the previous keeper's last activity. A return of 0 means "no record".
     */
    public long groupLastSeen(UUID owner, long now) {
        if (owner == null) {
            return now;
        }
        ConfigManager c = plugin.config();
        long best;
        if (!c.isStrictOwnershipEnabled() || !c.isCheckIpAlts()) {
            best = lastSeenOf(owner, now);
        } else {
            Set<UUID> group = plugin.ipRegistry().groupOf(owner, c.getMaxIpGroup());
            best = 0L;
            for (UUID member : group) {
                best = Math.max(best, lastSeenOf(member, now));
            }
        }
        // Enforced floor (auto-enforce-ip-links): an alt hand-off must not reset the timer,
        // so the effective last-seen can't be newer than the previous keeper's last activity.
        // Only honoured while enforcement is actually on, so toggling the feature off can't
        // leave a stale floor silently forcing a respawn.
        if (c.isStrictOwnershipEnabled() && c.isAutoEnforceIpLinks()) {
            long floor = plugin.store().getEnforcedClockFloor();
            if (floor > 0) {
                best = Math.min(best, floor);
            }
        }
        return best;
    }

    /** Single-account last-seen: online → now, else our map / IP registry / OfflinePlayer. */
    private long lastSeenOf(UUID uuid, long now) {
        if (Bukkit.getPlayer(uuid) != null) {
            return now;
        }
        return resolveLastSeen(uuid);
    }

    /**
     * Historical last-seen for an offline account: the newest of our own last-seen map,
     * the IP registry's last-login, and (as a fallback) the server's OfflinePlayer record.
     * 0 means "we genuinely have no record". Shared by the live clock and the transfer
     * inactivity check so the resolution rule lives in one place.
     */
    private long resolveLastSeen(UUID uuid) {
        long best = 0L;
        best = Math.max(best, plugin.store().getLastSeen(uuid));
        best = Math.max(best, plugin.ipRegistry().getLastLogin(uuid));
        if (best > 0) {
            return best;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        long last = offline.getLastSeen();
        return last > 0 ? last : 0L;
    }

    // ── Transfer evaluation ────────────────────────────────────────────────────

    /**
     * Called (via the data-store hook) the instant ownership changes to a new keeper.
     * Only does anything when strict-ownership is enabled. Posts an inbox alert if the
     * receiver looks like a linked alt, an inactive account, or part of a rapid
     * back-and-forth juggle, and — only when {@code auto-enforce-ip-links} is on AND it's
     * an IP link — refuses to let the transfer reset the respawn clock.
     *
     * @param receiverPriorSeen the receiver's last-seen BEFORE this transfer stamped it.
     */
    public void evaluateTransfer(UUID previous, UUID receiver, long receiverPriorSeen) {
        ConfigManager c = plugin.config();
        if (!c.isStrictOwnershipEnabled() || receiver == null) {
            return;
        }

        boolean linkedAlt = c.isCheckIpAlts() && previous != null
                && plugin.ipRegistry().sharesIpWithGroup(receiver, previous, c.getMaxIpGroup());
        boolean inactive = isInactiveReceiver(receiverPriorSeen, c.getMinReceiverActiveDays());
        boolean juggle = isRapidReversal(previous, receiver);

        recordTransfer(previous, receiver);

        if (!linkedAlt && !inactive && !juggle) {
            return; // looks like a normal, independent transfer — nothing to flag
        }

        String prevName = Players.name(previous);
        String recvName = Players.name(receiver);
        if (linkedAlt) {
            plugin.inbox().post(Severity.WARN, "IP-linked transfer",
                    "Egg passed from " + prevName + " to " + recvName
                            + " — they share a login IP (possible alt). "
                            + (c.isAutoEnforceIpLinks()
                                    ? "Auto-enforce ON: respawn clock NOT reset."
                                    : "Flag only; review and decide."),
                    previous, receiver);
        } else if (inactive) {
            plugin.inbox().post(Severity.INFO, "Inactive receiver",
                    "Egg passed from " + prevName + " to " + recvName
                            + " — receiver hadn't been active in the last "
                            + c.getMinReceiverActiveDays() + " day(s) before the hand-off.",
                    previous, receiver);
        }
        if (juggle) {
            plugin.inbox().post(Severity.WARN, "Rapid egg juggle",
                    "Egg bounced straight back between " + prevName + " and " + recvName
                            + " within 24h — possible two-account juggle to dodge the inactivity timer. "
                            + "Off-network alts evade IP checks; review manually.",
                    previous, receiver);
        }

        // Enforcement: treat a linked alt as the same entity for the clock by pinning the
        // inactivity floor to the previous keeper's last activity, which is what the
        // inactivity task actually reads (via groupLastSeen). Unlike the old approach,
        // this is not overwritten by the alt being online.
        if (linkedAlt && c.isAutoEnforceIpLinks() && previous != null) {
            long prevKeeperSeen = resolveLastSeen(previous);
            if (prevKeeperSeen > 0) {
                plugin.store().setEnforcedClock(prevKeeperSeen);
            }
        }
    }

    /** Was the receiver dormant (per their pre-transfer last-seen) for at least {@code minDays}? */
    private boolean isInactiveReceiver(long receiverPriorSeen, int minDays) {
        if (receiverPriorSeen <= 0) {
            // No prior record — can't claim they're inactive without evidence (and a
            // brand-new account is handled separately by the juggle/age heuristics).
            return false;
        }
        long age = System.currentTimeMillis() - receiverPriorSeen;
        return age >= TimeUnit.DAYS.toMillis(Math.max(1, minDays));
    }

    /** True if this transfer reverses the immediately-preceding one within 24h. */
    private boolean isRapidReversal(UUID previous, UUID receiver) {
        if (previous == null || receiver == null || lastTransferPrev == null) {
            return false;
        }
        boolean reversed = previous.equals(lastTransferRecv) && receiver.equals(lastTransferPrev);
        boolean recent = System.currentTimeMillis() - lastTransferAt <= TimeUnit.DAYS.toMillis(1);
        return reversed && recent;
    }

    private void recordTransfer(UUID previous, UUID receiver) {
        lastTransferPrev = previous;
        lastTransferRecv = receiver;
        lastTransferAt = System.currentTimeMillis();
    }
}
