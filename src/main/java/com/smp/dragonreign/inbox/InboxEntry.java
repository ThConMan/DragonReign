package com.smp.dragonreign.inbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable staff alert. {@code id} is a stable per-server counter so the GUI can
 * mark-read / dismiss a specific entry even as newer alerts shift the page around.
 */
public final class InboxEntry {

    private final long id;
    private final Severity severity;
    private final String type;
    private final String message;
    private final List<UUID> related;
    private final long epochMillis;
    private boolean read;

    public InboxEntry(long id, Severity severity, String type, String message,
                      List<UUID> related, long epochMillis, boolean read) {
        this.id = id;
        this.severity = severity != null ? severity : Severity.INFO;
        this.type = type != null ? type : "";
        this.message = message != null ? message : "";
        this.related = related != null ? new ArrayList<>(related) : new ArrayList<>();
        this.epochMillis = epochMillis;
        this.read = read;
    }

    public long id() { return id; }
    public Severity severity() { return severity; }
    public String type() { return type; }
    public String message() { return message; }
    public List<UUID> related() { return related; }
    public long epochMillis() { return epochMillis; }
    public boolean read() { return read; }

    public void markRead() {
        this.read = true;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("severity", severity.name());
        m.put("type", type);
        m.put("message", message);
        List<String> rel = new ArrayList<>();
        for (UUID u : related) {
            rel.add(u.toString());
        }
        m.put("related", rel);
        m.put("t", epochMillis);
        m.put("read", read);
        return m;
    }

    /** Rebuild from an inbox.yml map; null if it's too malformed to trust. */
    public static InboxEntry deserialize(Map<?, ?> m) {
        if (m == null) {
            return null;
        }
        try {
            long id = ((Number) m.get("id")).longValue();
            Severity sev = Severity.parse(m.get("severity") != null ? m.get("severity").toString() : null);
            String type = m.get("type") != null ? m.get("type").toString() : "";
            String message = m.get("message") != null ? m.get("message").toString() : "";
            List<UUID> related = new ArrayList<>();
            Object rawRel = m.get("related");
            if (rawRel instanceof List<?> list) {
                for (Object o : list) {
                    try {
                        related.add(UUID.fromString(o.toString()));
                    } catch (IllegalArgumentException ignored) {
                        // skip a bad UUID rather than dropping the whole alert
                    }
                }
            }
            long t = ((Number) m.get("t")).longValue();
            boolean read = Boolean.TRUE.equals(m.get("read"));
            return new InboxEntry(id, sev, type, message, related, t, read);
        } catch (Exception ex) {
            return null;
        }
    }
}
