package com.smp.dragonreign.victor;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.Perms;
import com.smp.dragonreign.inbox.Severity;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Players;
import com.smp.dragonreign.util.Yaml;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Tracks each player's lifetime active hold-time, the permanent set of "Dragonlords"
 * (victors), and their personal cosmetic on/off switches. Owns its own file
 * (victors.yml), saved through the shared atomic writer like the other stores.
 *
 * <p>Dragonlord is earned by TENURE, not by played time: hold the egg for
 * {@code victor.threshold-hours} of real elapsed time without losing it. Playtime can't
 * buy it and can't be saved up — losing the egg restarts the clock at zero (see
 * {@link #checkTenure}). Lifetime active hold-time is still tracked alongside it as a
 * stat, and staleness is what stops a keeper from simply logging off for the week.
 *
 * <p>The victor set only ever grows: once crowned, a player stays a Dragonlord even
 * after the egg moves on.
 */
public final class VictorManager {

    private final DragonReign plugin;
    private final File file;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, Long> activeHoldMillis = new ConcurrentHashMap<>();
    private final Set<UUID> victors = ConcurrentHashMap.newKeySet();
    // Manual revokes that must "stick" even while the player is still the egg owner: without
    // this, addActive() re-adds an already-earned owner to the victors set on the very next
    // accrual tick, re-promoting them and re-spamming. Cleared on a genuine ownership change
    // (HoldTimeTask) or an explicit admin grant. Persisted so a restart can't undo a revoke.
    private final Set<UUID> revoked = ConcurrentHashMap.newKeySet();
    // Toggles default ON; we only store an entry when a player turns one OFF.
    private final ConcurrentHashMap<UUID, Boolean> particleToggle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> titleToggle = new ConcurrentHashMap<>();

    // The resolved (colour-translated) title, cached so PlaceholderAPI's hot path
    // (%dragonreign_title%, requested for every player on a ~1s refresh loop) doesn't
    // re-read config + reallocate a String on every request. Invalidated on /dr reload.
    private volatile String cachedTitle;

    public VictorManager(DragonReign plugin, File file, Logger logger) {
        this.plugin = plugin;
        this.file = file;
        this.logger = logger;
    }

    // ── Active-time accrual ──────────────────────────────────────────────────────

    /**
     * Bank active held time as a lifetime stat. This no longer decides Dragonlord — see
     * {@link #checkTenure} — it stays because "how long has this account really carried
     * the egg" is worth keeping across the ladder resets that hand-offs cause.
     */
    public void addActive(UUID uuid, long deltaMillis) {
        if (uuid == null || deltaMillis <= 0) {
            return;
        }
        activeHoldMillis.merge(uuid, deltaMillis, Long::sum);
    }

    // ── Tenure (the Dragonlord clock) ────────────────────────────────────────────

    /**
     * Promote the current keeper once they have held the egg, unbroken, for the configured
     * number of real hours. {@code ownedSince} is the store's ownership stamp, so every
     * hand-off, respawn reset, and staleness reclaim restarts the week for free — this
     * method never has to know how the egg moved.
     *
     * <p>Called from the hold-time ticker with the keeper online, so the crowning flourish
     * always has someone to play for. An offline keeper simply gets crowned on their next
     * login: elapsed time keeps running either way, it is only the ceremony that waits.
     */
    public void checkTenure(UUID uuid, long ownedSince) {
        if (uuid == null || ownedSince <= 0 || victors.contains(uuid) || revoked.contains(uuid)) {
            // A manual revoke suppresses re-promotion until ownership changes — otherwise an
            // already-earned keeper is re-added (and re-announced) on the very next tick.
            return;
        }
        long held = System.currentTimeMillis() - ownedSince;
        long threshold = TimeUnit.HOURS.toMillis(plugin.config().getVictorThresholdHours());
        if (held >= threshold && victors.add(uuid)) {
            onEarnedVictor(uuid);
        }
    }

    /** Millis the current keeper still needs to hold the egg, or 0 once the week is served. */
    public long tenureRemaining(long ownedSince) {
        if (ownedSince <= 0) {
            return TimeUnit.HOURS.toMillis(plugin.config().getVictorThresholdHours());
        }
        long threshold = TimeUnit.HOURS.toMillis(plugin.config().getVictorThresholdHours());
        return Math.max(0L, threshold - (System.currentTimeMillis() - ownedSince));
    }

    private void onEarnedVictor(UUID uuid) {
        String name = Players.name(uuid);
        Player online = org.bukkit.Bukkit.getPlayer(uuid);
        if (online != null) {
            online.sendMessage(Msg.prefixed(plugin.config().getPrefix(),
                    plugin.config().getVictorEarnedMessage()));
        }
        int hours = plugin.config().getVictorThresholdHours();
        plugin.history().appendSystem(EventType.VICTOR_EARNED, null,
                name + " became a Dragonlord (held the egg " + hours + "h unbroken)");
        plugin.inbox().post(Severity.INFO, "New Dragonlord",
                name + " kept the egg for " + hours + " hours without losing it.", uuid);
        if (online != null) {
            plugin.playCoronation(online); // the crowning flourish
        }
        // Push the title to LuckPerms if that hook is on and they have it toggled on.
        if (titleEnabled(uuid)) {
            plugin.luckPerms().setTitle(uuid, plainTitle());
        }
    }

    // ── Victor status ────────────────────────────────────────────────────────────

    /** Set-membership only (use for offline lookups / persistence-backed checks). */
    public boolean isVictor(UUID uuid) {
        return uuid != null && victors.contains(uuid);
    }

    /**
     * Set membership OR an explicitly-assigned {@code dragonreign.victor} permission.
     * The node is intentionally left unregistered in plugin.yml (see the comment there),
     * so a staff member's '*' or a plain OP does NOT satisfy this — only an explicit grant,
     * the earned hold-time threshold, or {@code /dr victor grant} makes someone a Dragonlord.
     */
    public boolean isVictor(Player player) {
        return player != null && (victors.contains(player.getUniqueId())
                || player.hasPermission(Perms.VICTOR));
    }

    public long getActiveHoldMillis(UUID uuid) {
        Long v = uuid != null ? activeHoldMillis.get(uuid) : null;
        return v != null ? v : 0L;
    }

    // ── Cosmetic toggles (default ON) ──────────────────────────────────────────────

    public boolean particleEnabled(UUID uuid) {
        return particleToggle.getOrDefault(uuid, Boolean.TRUE);
    }

    public void setParticleEnabled(UUID uuid, boolean on) {
        if (uuid != null) {
            particleToggle.put(uuid, on);
        }
    }

    public boolean titleEnabled(UUID uuid) {
        return titleToggle.getOrDefault(uuid, Boolean.TRUE);
    }

    public void setTitleEnabled(UUID uuid, boolean on) {
        if (uuid == null) {
            return;
        }
        titleToggle.put(uuid, on);
        // Keep the LuckPerms meta in step with the toggle (no-op unless that hook is on).
        if (on && isVictor(uuid)) {
            plugin.luckPerms().setTitle(uuid, plainTitle());
        } else {
            plugin.luckPerms().clearTitle(uuid);
        }
    }

    // ── Admin grant / revoke ────────────────────────────────────────────────────────

    public void adminGrant(CommandSender by, UUID uuid) {
        if (uuid == null) {
            return;
        }
        revoked.remove(uuid); // an explicit grant lifts any standing revoke suppression
        if (victors.add(uuid)) {
            plugin.history().append(EventType.VICTOR_EARNED, by.getName(), uuid, null,
                    "granted Dragonlord to " + Players.name(uuid));
            Player online = org.bukkit.Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(Msg.prefixed(plugin.config().getPrefix(),
                        plugin.config().getVictorEarnedMessage()));
                plugin.playCoronation(online); // the crowning flourish
            }
            if (titleEnabled(uuid)) {
                plugin.luckPerms().setTitle(uuid, plainTitle());
            }
        }
    }

    public void adminRevoke(CommandSender by, UUID uuid) {
        if (uuid == null) {
            return;
        }
        victors.remove(uuid);
        // Suppress automatic re-promotion so the revoke sticks while the player is still the
        // egg owner (and across restarts); cleared when the egg next changes hands.
        revoked.add(uuid);
        plugin.luckPerms().clearTitle(uuid);
        plugin.history().append(EventType.ADMIN, by.getName(), uuid, null,
                "revoked Dragonlord from " + Players.name(uuid));
    }

    /**
     * Lift the revoke suppression for a player — called when the egg changes hands so a
     * past manual revoke doesn't block a fresh, legitimately-earned promotion later.
     */
    public void clearSuppression(UUID uuid) {
        if (uuid != null) {
            revoked.remove(uuid);
        }
    }

    // ── Title helpers ────────────────────────────────────────────────────────────

    /**
     * The configured title with legacy '&' codes turned into section colour codes.
     * Cached because PlaceholderAPI requests this on a hot per-player refresh loop; the
     * value only changes on /dr reload, when {@link #invalidateTitleCache()} clears it.
     */
    public String plainTitle() {
        String cached = cachedTitle;
        if (cached == null) {
            cached = ChatColor.translateAlternateColorCodes('&', plugin.config().getVictorTitle());
            cachedTitle = cached;
        }
        return cached;
    }

    /** Drop the cached title so the next read re-resolves it (called on config reload). */
    public void invalidateTitleCache() {
        cachedTitle = null;
    }

    // ── Persistence ────────────────────────────────────────────────────────────────

    public void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                logger.warning("Ignoring malformed player uuid in victors.yml: " + key);
                continue;
            }
            long held = players.getLong(key + ".active-hold-millis", 0L);
            if (held > 0) {
                activeHoldMillis.put(uuid, held);
            }
            if (players.getBoolean(key + ".victor", false)) {
                victors.add(uuid);
            }
            if (players.getBoolean(key + ".revoked", false)) {
                revoked.add(uuid);
            }
            if (players.contains(key + ".particle")) {
                particleToggle.put(uuid, players.getBoolean(key + ".particle"));
            }
            if (players.contains(key + ".title")) {
                titleToggle.put(uuid, players.getBoolean(key + ".title"));
            }
        }
    }

    public YamlConfiguration buildSnapshotYaml() {
        YamlConfiguration out = new YamlConfiguration();
        Set<UUID> all = new HashSet<>();
        all.addAll(activeHoldMillis.keySet());
        all.addAll(victors);
        all.addAll(revoked);
        all.addAll(particleToggle.keySet());
        all.addAll(titleToggle.keySet());
        for (UUID uuid : all) {
            String base = "players." + uuid;
            long held = getActiveHoldMillis(uuid);
            if (held > 0) {
                out.set(base + ".active-hold-millis", held);
            }
            if (victors.contains(uuid)) {
                out.set(base + ".victor", true);
            }
            if (revoked.contains(uuid)) {
                out.set(base + ".revoked", true);
            }
            Boolean particle = particleToggle.get(uuid);
            if (particle != null) {
                out.set(base + ".particle", particle);
            }
            Boolean title = titleToggle.get(uuid);
            if (title != null) {
                out.set(base + ".title", title);
            }
        }
        return out;
    }

    public void write(YamlConfiguration yaml) {
        Yaml.saveAtomic(file, yaml, logger);
    }

    public void saveSync() {
        write(buildSnapshotYaml());
    }
}
