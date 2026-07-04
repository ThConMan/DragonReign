package com.smp.dragonreign.listener;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.util.Msg;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a PvP kill by the egg's current keeper into bonus progress on the hold-reward
 * bar. Besides simply holding the egg over time, this is the only thing that fills the
 * bar — so it is gated hard against the obvious farm: feeding yourself your own alts.
 *
 * <p>A kill only pays when the killer is the current keeper AND the victim is a real,
 * independent, established account: not the killer, not sharing the killer's login IP,
 * past a minimum server playtime, and not the same victim again inside a cooldown window.
 * Each qualifying kill banks a configurable slice of "active hold time" onto the egg via
 * the normal reward path, so a kill can complete an interval and pay out a tier exactly
 * like held time does. Kills feed only the per-egg reward ladder, never the lifetime
 * Dragonlord (victor) progress — that stays pure time.
 */
public final class KillBonusListener implements Listener {

    private final DragonReign plugin;

    // (killer:victim) -> last millis we credited that killer for killing that victim. Stops
    // one keeper from farming the same opponent on repeat. Small and self-limiting: bounded
    // by the distinct kill pairs seen since the last restart, and kills are infrequent.
    private final Map<String, Long> lastCredited = new ConcurrentHashMap<>();

    public KillBonusListener(DragonReign plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.config().isKillBonusEnabled() || !plugin.config().isRewardsEnabled()) {
            return;
        }
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return; // no player killer (mob/environment/self) — never counts
        }
        UUID owner = plugin.store().getOwner();
        if (owner == null || !owner.equals(killer.getUniqueId())) {
            return; // only the egg's current keeper earns from kills
        }
        UUID victimId = victim.getUniqueId();

        // ── Anti-alt gates ────────────────────────────────────────────────────
        // 1) Same connection: killing an account that shares the killer's login IP (or is in
        //    their IP-linked group) is worth nothing. IP hashes are recorded on every join
        //    regardless of the strict-ownership toggle, so this gate works everywhere.
        if (plugin.config().isKillBonusIgnoreIpLinked()
                && plugin.ipRegistry().sharesIpWithGroup(victimId, owner, plugin.config().getMaxIpGroup())) {
            return;
        }
        // 2) Fresh account: a victim under the minimum server playtime gives nothing, so
        //    spinning up throwaway accounts to feed yourself is pointless.
        int minHours = plugin.config().getKillBonusMinVictimPlaytimeHours();
        if (minHours > 0 && victimPlaytimeHours(victim) < minHours) {
            return;
        }
        // 3) Repeat kill: the same victim pays this killer at most once per cooldown window.
        long cooldownMillis = plugin.config().getKillBonusSameVictimCooldownHours() * 3_600_000L;
        long now = System.currentTimeMillis();
        if (cooldownMillis > 0) {
            String key = owner + ":" + victimId;
            Long last = lastCredited.get(key);
            if (last != null && now - last < cooldownMillis) {
                return;
            }
            lastCredited.put(key, now);
        }

        // Bank the bonus as active-hold-time-equivalent onto the egg's reward bar. This runs
        // the same path held time uses, so it can tip the bar over an interval and pay out.
        long bonusMillis = plugin.config().getKillBonusMinutes() * 60_000L;
        if (bonusMillis <= 0) {
            return;
        }
        plugin.rewards().addActive(owner, bonusMillis);

        String msg = plugin.config().getKillBonusMessage();
        if (msg != null && !msg.isEmpty()) {
            killer.sendMessage(Msg.prefixed(plugin.config().getPrefix(), msg));
        }
    }

    /**
     * Server playtime in whole hours. {@code PLAY_ONE_MINUTE} is (misleadingly) a play-time
     * <i>tick</i> counter. If the statistic can't be read we return a large value so a stat
     * hiccup never blocks a legitimate kill.
     */
    private int victimPlaytimeHours(Player victim) {
        try {
            long ticks = victim.getStatistic(Statistic.PLAY_ONE_MINUTE);
            return (int) Math.min(Integer.MAX_VALUE, ticks / 72_000L); // 20 ticks/s * 3600 s/h
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }
}
