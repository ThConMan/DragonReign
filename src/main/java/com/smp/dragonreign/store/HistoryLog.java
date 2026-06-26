package com.smp.dragonreign.store;

import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.model.HistoryEntry;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * A capped, append-only log. Newest entries live at the tail; when we exceed the
 * configured cap the oldest (head) entries are evicted.
 */
public final class HistoryLog {

    private final ConfigManager config;
    private final Logger logger;
    private final Deque<HistoryEntry> entries = new ArrayDeque<>();
    private final Object lock = new Object();

    public HistoryLog(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void append(EventType type, String playerName, UUID uuid, EggLocation loc, String detail) {
        HistoryEntry entry = new HistoryEntry(System.currentTimeMillis(), type, playerName, uuid, loc, detail);
        synchronized (lock) {
            entries.addLast(entry);
            int cap = config.getMaxHistory();
            while (entries.size() > cap) {
                entries.removeFirst();
            }
        }
    }

    public void append(EventType type, Player player, EggLocation loc, String detail) {
        append(type, player != null ? player.getName() : "SYSTEM",
                player != null ? player.getUniqueId() : null, loc, detail);
    }

    public void append(EventType type, OfflinePlayer player, EggLocation loc, String detail) {
        String name = player != null && player.getName() != null ? player.getName() : "SYSTEM";
        UUID id = player != null ? player.getUniqueId() : null;
        append(type, name, id, loc, detail);
    }

    public void appendSystem(EventType type, EggLocation loc, String detail) {
        append(type, "SYSTEM", null, loc, detail);
    }

    /** Newest-first page for the GUI. Page is 0-based. */
    public List<HistoryEntry> recentNewestFirst(int page, int pageSize) {
        List<HistoryEntry> all = newestFirstCopy();
        int from = page * pageSize;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(from + pageSize, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    public int totalPages(int pageSize) {
        int size;
        synchronized (lock) {
            size = entries.size();
        }
        if (size == 0) {
            return 1;
        }
        return (size + pageSize - 1) / pageSize;
    }

    private List<HistoryEntry> newestFirstCopy() {
        List<HistoryEntry> copy;
        synchronized (lock) {
            copy = new ArrayList<>(entries);
        }
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** Oldest-first copy for persistence (matches the on-disk convention). */
    public List<HistoryEntry> snapshotOldestFirst() {
        synchronized (lock) {
            return new ArrayList<>(entries);
        }
    }

    public void loadFrom(List<?> raw) {
        synchronized (lock) {
            entries.clear();
            if (raw == null) {
                return;
            }
            int skipped = 0;
            for (Object o : raw) {
                if (!(o instanceof java.util.Map<?, ?> map)) {
                    skipped++;
                    continue;
                }
                HistoryEntry entry = HistoryEntry.deserialize(map);
                if (entry == null) {
                    skipped++;
                } else {
                    entries.addLast(entry);
                }
            }
            int cap = config.getMaxHistory();
            while (entries.size() > cap) {
                entries.removeFirst();
            }
            if (skipped > 0) {
                logger.warning("Skipped " + skipped + " malformed history entries while loading data.yml.");
            }
        }
    }
}
