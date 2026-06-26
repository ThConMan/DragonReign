package com.smp.dragonreign.inbox;

import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Yaml;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The staff alert queue. Anything the plugin wants a human to eventually see — an
 * IP-link flag, a respawn countdown firing, a dupe it just cleaned up — lands here.
 *
 * <p>Routing rule (DESIGN §8.5): if any admin is online when an alert is posted, DM
 * them right away; if nobody's online, just queue it. Either way it's always queued,
 * so the alert survives until someone opens {@code /dragonreign inbox}.
 */
public final class Inbox {

    private static final String ADMIN = "dragonreign.admin";

    private final ConfigManager config;
    private final File file;
    private final Logger logger;

    private final Deque<InboxEntry> entries = new ArrayDeque<>();
    private final Object lock = new Object();
    private long nextId = 1L;

    public Inbox(ConfigManager config, File file, Logger logger) {
        this.config = config;
        this.file = file;
        this.logger = logger;
    }

    // ── Posting / routing ─────────────────────────────────────────────────────

    /** Queue an alert and (if any admin is online) DM it immediately. No-op when the inbox is disabled. */
    public void post(Severity severity, String type, String message, UUID... related) {
        if (!config.isInboxEnabled()) {
            return;
        }
        // Callers routinely pass a nullable "previous owner", so filter nulls rather than
        // letting List.of(...) blow up on them.
        List<UUID> rel = new ArrayList<>();
        if (related != null) {
            for (UUID u : related) {
                if (u != null) {
                    rel.add(u);
                }
            }
        }
        InboxEntry entry;
        synchronized (lock) {
            entry = new InboxEntry(nextId++, severity, type, message,
                    rel, System.currentTimeMillis(), false);
            entries.addLast(entry);
            int cap = config.getInboxMaxEntries();
            while (entries.size() > cap) {
                entries.removeFirst();
            }
        }
        route(entry);
    }

    private void route(InboxEntry entry) {
        String prefix = config.getPrefix();
        String body = "<" + entry.severity().color() + ">[" + entry.severity().name() + "]</"
                + entry.severity().color() + "> <white>" + Msg.escape(entry.type()) + "</white> "
                + "<gray>" + Msg.escape(entry.message()) + "</gray>";
        boolean anyAdmin = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(ADMIN)) {
                anyAdmin = true;
                p.sendMessage(Msg.prefixed(prefix, body));
                p.sendMessage(Msg.mm("<dark_gray>  → <gray>/dragonreign inbox</gray></dark_gray>"));
            }
        }
        if (!anyAdmin) {
            // Nobody to tell live — it waits in the queue and we leave a console breadcrumb.
            logger.info("Inbox [" + entry.severity().name() + "] " + entry.type() + " — " + entry.message()
                    + " (queued; no admin online)");
        }
    }

    // ── Reads / mutations for the GUI ──────────────────────────────────────────

    public int unreadCount() {
        synchronized (lock) {
            int n = 0;
            for (InboxEntry e : entries) {
                if (!e.read()) {
                    n++;
                }
            }
            return n;
        }
    }

    public void markRead(long id) {
        synchronized (lock) {
            for (InboxEntry e : entries) {
                if (e.id() == id) {
                    e.markRead();
                    return;
                }
            }
        }
    }

    public void dismiss(long id) {
        synchronized (lock) {
            entries.removeIf(e -> e.id() == id);
        }
    }

    /** Newest-first page for the GUI. Page is 0-based. */
    public List<InboxEntry> recentNewestFirst(int page, int pageSize) {
        List<InboxEntry> all = newestFirstCopy();
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

    private List<InboxEntry> newestFirstCopy() {
        List<InboxEntry> copy;
        synchronized (lock) {
            copy = new ArrayList<>(entries);
        }
        java.util.Collections.reverse(copy);
        return copy;
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    public void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        synchronized (lock) {
            entries.clear();
            nextId = Math.max(1L, data.getLong("next-id", 1L));
            List<?> raw = data.getList("entries");
            int skipped = 0;
            if (raw != null) {
                for (Object o : raw) {
                    if (!(o instanceof Map<?, ?> map)) {
                        skipped++;
                        continue;
                    }
                    InboxEntry e = InboxEntry.deserialize(map);
                    if (e == null) {
                        skipped++;
                    } else {
                        entries.addLast(e);
                        // Keep the counter ahead of anything we loaded so ids stay unique.
                        if (e.id() >= nextId) {
                            nextId = e.id() + 1;
                        }
                    }
                }
            }
            int cap = config.getInboxMaxEntries();
            while (entries.size() > cap) {
                entries.removeFirst();
            }
            if (skipped > 0) {
                logger.warning("Skipped " + skipped + " malformed inbox entries while loading inbox.yml.");
            }
        }
    }

    /** Snapshot to YAML on the main thread; the disk write itself can run off-thread. */
    public YamlConfiguration buildSnapshotYaml() {
        YamlConfiguration out = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        synchronized (lock) {
            out.set("next-id", nextId);
            for (InboxEntry e : entries) {
                list.add(e.serialize());
            }
        }
        out.set("entries", list);
        return out;
    }

    public void write(YamlConfiguration yaml) {
        Yaml.saveAtomic(file, yaml, logger);
    }

    public void saveSync() {
        write(buildSnapshotYaml());
    }
}
