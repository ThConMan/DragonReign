package com.smp.dragonreign.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One immutable line in the history log. */
public final class HistoryEntry {

    private final long epochMillis;
    private final EventType type;
    private final String playerName;
    private final UUID playerUuid;   // nullable (system events)
    private final EggLocation location; // nullable
    private final String detail;

    public HistoryEntry(long epochMillis, EventType type, String playerName,
                        UUID playerUuid, EggLocation location, String detail) {
        this.epochMillis = epochMillis;
        this.type = type;
        this.playerName = playerName != null ? playerName : "SYSTEM";
        this.playerUuid = playerUuid;
        this.location = location;
        this.detail = detail != null ? detail : "";
    }

    public long epochMillis() { return epochMillis; }
    public EventType type() { return type; }
    public String playerName() { return playerName; }
    public UUID playerUuid() { return playerUuid; }
    public EggLocation location() { return location; }
    public String detail() { return detail; }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", epochMillis);
        m.put("type", type.name());
        m.put("player", playerName);
        m.put("uuid", playerUuid != null ? playerUuid.toString() : null);
        m.put("loc", location != null ? location.compact() : "-");
        m.put("detail", detail);
        return m;
    }

    /** Rebuild from a data.yml map; returns null for malformed entries (skip + warn upstream). */
    public static HistoryEntry deserialize(Map<?, ?> m) {
        if (m == null) {
            return null;
        }
        try {
            long t = ((Number) m.get("t")).longValue();
            EventType type = EventType.valueOf(m.get("type").toString());
            String player = m.get("player") != null ? m.get("player").toString() : "SYSTEM";
            UUID uuid = null;
            Object rawUuid = m.get("uuid");
            if (rawUuid != null && !rawUuid.toString().isEmpty()) {
                uuid = UUID.fromString(rawUuid.toString());
            }
            EggLocation loc = EggLocation.fromCompact(m.get("loc") != null ? m.get("loc").toString() : null);
            String detail = m.get("detail") != null ? m.get("detail").toString() : "";
            return new HistoryEntry(t, type, player, uuid, loc, detail);
        } catch (Exception ex) {
            return null;
        }
    }
}
