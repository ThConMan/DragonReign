package com.smp.dragonreign.model;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live, mutable tracking state for the conceptual single egg. Mutated only on
 * the main thread; the concurrent collections let the snapshot copy stay cheap and
 * consistent for the async writer.
 */
public final class EggState {

    public UUID ownerUuid;            // null when unowned
    public EggLocation location;      // null when held (not placed)
    public long lastActivity;
    public long enforcedClockFloor;   // 0 = none; see EggDataStore.getEnforcedClockFloor

    public final ConcurrentHashMap<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    public final Set<UUID> pendingErase = ConcurrentHashMap.newKeySet();

    // Eggs pulled out of a player's death drops, owed back to them on their next
    // respawn/join. Persisted so a crash between death and respawn can't lose the
    // unique egg (mirrors pendingErase). Cleared wholesale when the egg respawns.
    public final ConcurrentHashMap<UUID, Integer> pendingGive = new ConcurrentHashMap<>();

    // Every account that has ever held the egg. At respawn time we flag all of them
    // for erase-on-join, so a stray copy stashed by an OFFLINE non-owner can't coexist
    // with the freshly spawned egg until they happen to be swept while online.
    public final Set<UUID> knownHolders = ConcurrentHashMap.newKeySet();
}
