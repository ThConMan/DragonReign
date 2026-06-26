package com.smp.dragonreign.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable point-in-time copy of everything that needs persisting. Built on
 * the main thread, then handed to the async writer so it never reads live state.
 */
public final class EggSnapshot {

    public final UUID owner;
    public final EggLocation location;
    public final long lastActivity;
    public final long enforcedClockFloor;
    public final Map<UUID, Long> lastSeen;
    public final List<UUID> pendingErase;
    public final Map<UUID, Integer> pendingGive;
    public final Collection<UUID> knownHolders;
    public final List<HistoryEntry> history;

    public EggSnapshot(UUID owner, EggLocation location, long lastActivity, long enforcedClockFloor,
                       Map<UUID, Long> lastSeen, List<UUID> pendingErase,
                       Map<UUID, Integer> pendingGive, Collection<UUID> knownHolders,
                       List<HistoryEntry> history) {
        this.owner = owner;
        this.location = location;
        this.lastActivity = lastActivity;
        this.enforcedClockFloor = enforcedClockFloor;
        this.lastSeen = lastSeen;
        this.pendingErase = pendingErase;
        this.pendingGive = pendingGive;
        this.knownHolders = knownHolders;
        this.history = history;
    }
}
