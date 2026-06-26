package com.smp.dragonreign.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/** Tiny shared helpers for resolving player identities. Stateless. */
public final class Players {

    private Players() {
    }

    /**
     * Best-effort display name for a UUID: the known OfflinePlayer name, or the raw
     * UUID string when we've never seen them. Never returns null. A null UUID maps to
     * {@code "unknown"} (callers that want a different placeholder pass their own).
     */
    public static String name(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString();
    }
}
