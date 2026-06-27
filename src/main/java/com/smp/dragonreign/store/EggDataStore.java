package com.smp.dragonreign.store;

import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.model.EggSnapshot;
import com.smp.dragonreign.model.EggState;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.model.HistoryEntry;
import com.smp.dragonreign.util.Yaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Owns data.yml: the egg tracking state plus the shared history log. The store and
 * the log write through one file via {@link #buildSnapshotYaml()} so two writers
 * never race on the same path.
 */
public final class EggDataStore {

    private final File file;
    private final Logger logger;
    private final HistoryLog history;
    private final EggState state = new EggState();

    // v2: fired the moment ownership genuinely changes to a non-null new keeper. The
    // three transfer call-sites (place / pickup / /giveegg) all flow through setOwner,
    // so wiring one hook here means the strict-ownership evaluation lives in exactly one
    // place instead of being copy-pasted into each listener. Nullable = nobody listening.
    private OwnerChangeHook ownerChangedHook;

    // v1.2: tells setOwner whether losing the egg should wipe the hold-reward ladder.
    // Null = always reset (the default). Wired to config.isRewardResetOnLoss() so the
    // "keep progress across hand-offs" option only has to live in one place.
    private java.util.function.BooleanSupplier rewardResetPolicy;

    /**
     * Owner-change callback. {@code previous} may be null (egg was unowned).
     * {@code receiverPriorSeen} is the receiver's last-seen as it stood BEFORE this
     * transfer stamped it to "now" — so strict-ownership can judge historical activity
     * instead of the always-true "they're online at hand-off".
     */
    @FunctionalInterface
    public interface OwnerChangeHook {
        void accept(UUID previous, UUID receiver, long receiverPriorSeen);
    }

    public EggDataStore(File file, Logger logger, HistoryLog history) {
        this.file = file;
        this.logger = logger;
        this.history = history;
    }

    /** Wire the strict-ownership evaluator. */
    public void setOwnerChangedHook(OwnerChangeHook hook) {
        this.ownerChangedHook = hook;
    }

    /** Wire the "should losing the egg reset the hold-reward ladder?" check. */
    public void setRewardResetPolicy(java.util.function.BooleanSupplier policy) {
        this.rewardResetPolicy = policy;
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    public void load() {
        if (!file.exists()) {
            state.lastActivity = System.currentTimeMillis();
            return;
        }
        FileConfiguration data = YamlConfiguration.loadConfiguration(file);

        String ownerRaw = data.getString("egg.owner");
        if (ownerRaw != null && !ownerRaw.isEmpty()) {
            try {
                state.ownerUuid = UUID.fromString(ownerRaw);
            } catch (IllegalArgumentException ex) {
                logger.warning("Ignoring malformed egg.owner in data.yml: " + ownerRaw);
            }
        }

        ConfigurationSection locSec = data.getConfigurationSection("egg.location");
        if (locSec != null) {
            state.location = EggLocation.deserialize(locSec.getValues(false));
        }

        state.lastActivity = data.getLong("egg.last-activity", System.currentTimeMillis());
        state.enforcedClockFloor = data.getLong("egg.enforced-clock-floor", 0L);
        state.rewardTier = Math.max(0, data.getInt("egg.reward-tier", 0));
        state.rewardProgressMillis = Math.max(0L, data.getLong("egg.reward-progress", 0L));

        ConfigurationSection seen = data.getConfigurationSection("last-seen");
        if (seen != null) {
            for (String key : seen.getKeys(false)) {
                try {
                    state.lastSeen.put(UUID.fromString(key), seen.getLong(key));
                } catch (IllegalArgumentException ex) {
                    logger.warning("Ignoring malformed last-seen uuid: " + key);
                }
            }
        }

        for (String raw : data.getStringList("pending-erase")) {
            try {
                state.pendingErase.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ex) {
                logger.warning("Ignoring malformed pending-erase uuid: " + raw);
            }
        }

        ConfigurationSection give = data.getConfigurationSection("pending-give");
        if (give != null) {
            for (String key : give.getKeys(false)) {
                try {
                    int count = give.getInt(key);
                    if (count > 0) {
                        state.pendingGive.put(UUID.fromString(key), count);
                    }
                } catch (IllegalArgumentException ex) {
                    logger.warning("Ignoring malformed pending-give uuid: " + key);
                }
            }
        }

        for (String raw : data.getStringList("known-holders")) {
            try {
                state.knownHolders.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ex) {
                logger.warning("Ignoring malformed known-holder uuid: " + raw);
            }
        }

        history.loadFrom(data.getList("history"));
    }

    // ── Egg tracking accessors (main thread) ────────────────────────────────

    public UUID getOwner() {
        return state.ownerUuid;
    }

    /**
     * Set/clear the owner. When it actually changes we record the new owner as
     * "seen now" and log OWNER_CHANGED with the supplied reason.
     */
    public void setOwner(UUID newOwner, String reason) {
        UUID old = state.ownerUuid;
        boolean changed = (old == null) ? (newOwner != null) : !old.equals(newOwner);
        // Capture the receiver's last-seen BEFORE markSeen stamps it to "now", so the
        // strict-ownership evaluator can tell a long-dormant mule from a regular.
        long receiverPriorSeen = newOwner != null ? getLastSeen(newOwner) : 0L;
        state.ownerUuid = newOwner;
        if (newOwner != null) {
            markSeen(newOwner);
            state.knownHolders.add(newOwner); // remember every account that ever held it
        }
        if (changed) {
            // Any genuine ownership change voids a prior enforced clock; an enforced
            // IP-link transfer re-establishes it inside the hook below.
            clearEnforcedClock();

            // Losing the egg resets the hold-reward ladder for the new keeper, unless the
            // server turned that off. Covers transfer, pickup, and the respawn reset
            // (setOwner(null, ...)) — every path ownership changes flows through here.
            if (rewardResetPolicy == null || rewardResetPolicy.getAsBoolean()) {
                state.rewardTier = 0;
                state.rewardProgressMillis = 0L;
            }

            String detail = "from " + (old != null ? old : "none") + " to "
                    + (newOwner != null ? newOwner : "none")
                    + (reason != null ? " (" + reason + ")" : "");
            // No location here: callers update it right after setOwner, so state.location
            // is still the PREVIOUS spot. The paired PLACED/PICKED_UP/TRANSFER entry
            // already records the correct location.
            history.append(EventType.OWNER_CHANGED, "SYSTEM", newOwner, null, detail);

            // Only a transfer to a real new keeper is worth evaluating; the respawn
            // reset (newOwner == null) isn't a transfer.
            if (newOwner != null && ownerChangedHook != null) {
                ownerChangedHook.accept(old, newOwner, receiverPriorSeen);
            }
        }
    }

    public EggLocation getLocation() {
        return state.location;
    }

    public void setLocation(EggLocation location) {
        state.location = location;
    }

    public void clearLocation() {
        state.location = null;
    }

    public void touchActivity() {
        state.lastActivity = System.currentTimeMillis();
    }

    public long getLastActivity() {
        return state.lastActivity;
    }

    /**
     * Enforced inactivity-clock floor: when {@code auto-enforce-ip-links} catches an
     * alt-to-alt hand-off, we pin the effective last-seen the inactivity task uses to the
     * previous keeper's last activity, so the alt being online can't reset the timer.
     * 0 means "no enforced floor". This drives the SAME signal InactivityTask reads
     * (group last-seen), unlike the old no-op that wrote a field nothing consulted.
     */
    public long getEnforcedClockFloor() {
        return state.enforcedClockFloor;
    }

    public void setEnforcedClock(long floorMillis) {
        state.enforcedClockFloor = floorMillis;
    }

    public void clearEnforcedClock() {
        state.enforcedClockFloor = 0L;
    }

    // ── Hold-reward ladder (current keeper) ──────────────────────────────────

    public int getRewardTier() {
        return state.rewardTier;
    }

    public void setRewardTier(int tier) {
        state.rewardTier = Math.max(0, tier);
    }

    public long getRewardProgressMillis() {
        return state.rewardProgressMillis;
    }

    public void setRewardProgressMillis(long millis) {
        state.rewardProgressMillis = Math.max(0L, millis);
    }

    public void markSeen(UUID uuid) {
        if (uuid != null) {
            state.lastSeen.put(uuid, System.currentTimeMillis());
        }
    }

    /** Last-seen we have on record, or 0 if we've never tracked this player. */
    public long getLastSeen(UUID uuid) {
        Long v = uuid != null ? state.lastSeen.get(uuid) : null;
        return v != null ? v : 0L;
    }

    public void addPendingErase(UUID uuid) {
        if (uuid != null) {
            state.pendingErase.add(uuid);
        }
    }

    /** Returns true and removes the flag if the player was pending erasure. */
    public boolean consumePendingErase(UUID uuid) {
        return uuid != null && state.pendingErase.remove(uuid);
    }

    // ── Pending give-back (death-held eggs, persisted) ───────────────────────

    /** Record that {@code count} egg(s) are owed back to this player (e.g. pulled from death drops). */
    public void addPendingGive(UUID uuid, int count) {
        if (uuid != null && count > 0) {
            state.pendingGive.merge(uuid, count, Integer::sum);
        }
    }

    /** Take and clear the eggs owed to this player; returns 0 if none. */
    public int consumePendingGive(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        Integer owed = state.pendingGive.remove(uuid);
        return owed != null ? owed : 0;
    }

    /**
     * Drop every owed give-back (called when the egg respawns — any death-held copy is
     * now void). Returns how many egg items were discarded, for the audit log.
     */
    public int clearAllPendingGive() {
        int total = 0;
        for (Integer v : state.pendingGive.values()) {
            total += v != null ? v : 0;
        }
        state.pendingGive.clear();
        return total;
    }

    // ── Known holders (for respawn-time erase flagging) ──────────────────────

    /** Snapshot of every account that has ever held the egg. */
    public java.util.Set<UUID> knownHolders() {
        return new java.util.HashSet<>(state.knownHolders);
    }

    public HistoryLog history() {
        return history;
    }

    // ── Snapshot + persistence ──────────────────────────────────────────────

    /** Immutable copy taken on the main thread for the async writer. */
    public EggSnapshot snapshot() {
        return new EggSnapshot(
                state.ownerUuid,
                state.location,
                state.lastActivity,
                state.enforcedClockFloor,
                state.rewardTier,
                state.rewardProgressMillis,
                new LinkedHashMap<>(state.lastSeen),
                new ArrayList<>(state.pendingErase),
                new LinkedHashMap<>(state.pendingGive),
                new ArrayList<>(state.knownHolders),
                history.snapshotOldestFirst());
    }

    /** Build the YAML on the main thread; the actual disk write can run anywhere. */
    public YamlConfiguration buildSnapshotYaml() {
        EggSnapshot snap = snapshot();
        YamlConfiguration out = new YamlConfiguration();

        if (snap.owner != null) {
            out.set("egg.owner", snap.owner.toString());
        }
        if (snap.location != null) {
            out.set("egg.location", snap.location.serialize());
        }
        out.set("egg.last-activity", snap.lastActivity);
        if (snap.enforcedClockFloor > 0) {
            out.set("egg.enforced-clock-floor", snap.enforcedClockFloor);
        }
        if (snap.rewardTier > 0) {
            out.set("egg.reward-tier", snap.rewardTier);
        }
        if (snap.rewardProgressMillis > 0) {
            out.set("egg.reward-progress", snap.rewardProgressMillis);
        }

        // Only the current owner's last-seen is ever read back; persisting the whole
        // playerbase would bloat data.yml indefinitely. This also prunes any legacy
        // entries that were written before this rule existed.
        if (snap.owner != null) {
            Long ownerSeen = snap.lastSeen.get(snap.owner);
            if (ownerSeen != null) {
                out.set("last-seen." + snap.owner, ownerSeen);
            }
        }

        List<String> pending = new ArrayList<>();
        for (UUID u : snap.pendingErase) {
            pending.add(u.toString());
        }
        out.set("pending-erase", pending);

        for (Map.Entry<UUID, Integer> e : snap.pendingGive.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                out.set("pending-give." + e.getKey(), e.getValue());
            }
        }

        List<String> holders = new ArrayList<>();
        for (UUID u : snap.knownHolders) {
            holders.add(u.toString());
        }
        out.set("known-holders", holders);

        List<Map<String, Object>> hist = new ArrayList<>();
        for (HistoryEntry entry : snap.history) {
            hist.add(entry.serialize());
        }
        out.set("history", hist);

        return out;
    }

    /** Synchronous write — used on disable when async tasks may be gone. */
    public void saveSync() {
        write(buildSnapshotYaml());
    }

    public void write(YamlConfiguration yaml) {
        // Shared atomic temp-then-swap writer (see util.Yaml); per-path locking lives there.
        Yaml.saveAtomic(file, yaml, logger);
    }
}
