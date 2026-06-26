package com.smp.dragonreign.ownership;

import com.smp.dragonreign.util.Yaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Remembers, per player, a set of <b>salted SHA-256 hashes</b> of the IPs they've
 * logged in from, plus their last-login timestamp. Raw IPs are NEVER stored — only
 * the digest — so this file is useless for tracking someone's address even if leaked,
 * and the per-server salt means digests aren't comparable across servers.
 *
 * <p>The hashes let us answer the only question strict-ownership cares about: "have
 * these two accounts ever connected from the same place?" without us ever knowing
 * where that place is.
 */
public final class IpRegistry {

    private final File file;
    private final Logger logger;

    // uuid -> set of salted IP hashes; uuid -> last login millis. Concurrent so the
    // snapshot-for-save copy stays cheap and consistent.
    private final Map<UUID, Set<String>> hashes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLogin = new ConcurrentHashMap<>();

    public IpRegistry(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    // ── Recording ──────────────────────────────────────────────────────────────

    /**
     * Hash this player's current login IP (with the salt) and record it, and stamp
     * their last-login as now. Gracefully no-ops if the address is unavailable.
     */
    public void recordLogin(Player player, String salt) {
        UUID uuid = player.getUniqueId();
        lastLogin.put(uuid, System.currentTimeMillis());

        InetSocketAddress socket = player.getAddress();
        if (socket == null || socket.getAddress() == null) {
            return; // no address to hash (rare edge / some proxies) — last-login still updated
        }
        String hash = hash(salt, socket.getAddress().getHostAddress());
        if (hash != null) {
            hashes.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(hash);
        }
    }

    private String hash(String salt, String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS, so this should be unreachable.
            logger.severe("SHA-256 unavailable; cannot record IP hash: " + ex.getMessage());
            return null;
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public long getLastLogin(UUID uuid) {
        Long v = uuid != null ? lastLogin.get(uuid) : null;
        return v != null ? v : 0L;
    }

    /** Do these two accounts share at least one login-IP hash? */
    public boolean sharesIp(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        Set<String> ha = hashes.get(a);
        Set<String> hb = hashes.get(b);
        if (ha == null || hb == null) {
            return false;
        }
        for (String h : ha) {
            if (hb.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The set of accounts IP-linked to {@code anchor}, including the anchor itself:
     * anyone who has ever shared a login-IP hash with it. O(players) — fine for any
     * realistic playerbase, and only called on the minutes-scale inactivity tick.
     */
    public Set<UUID> groupOf(UUID anchor) {
        return groupOf(anchor, 0);
    }

    /**
     * Like {@link #groupOf(UUID)}, but ignores any IP hash shared by more than
     * {@code maxAccountsPerHash} distinct accounts. A hash that hundreds of unrelated
     * players present is a CGNAT/mobile/VPN/shared-LAN endpoint, not an alt ring — linking
     * through it lets an inactive keeper borrow strangers' fresh logins to keep the egg
     * immortal. {@code maxAccountsPerHash <= 0} disables the cap (legacy behaviour).
     */
    public Set<UUID> groupOf(UUID anchor, int maxAccountsPerHash) {
        Set<UUID> group = new HashSet<>();
        if (anchor == null) {
            return group;
        }
        group.add(anchor);
        Set<String> mine = hashes.get(anchor);
        if (mine == null || mine.isEmpty()) {
            return group;
        }
        for (Map.Entry<UUID, Set<String>> e : hashes.entrySet()) {
            if (e.getKey().equals(anchor)) {
                continue;
            }
            for (String h : e.getValue()) {
                if (mine.contains(h) && !isOverShared(h, maxAccountsPerHash)) {
                    group.add(e.getKey());
                    break;
                }
            }
        }
        return group;
    }

    /** True if {@code maxAccountsPerHash} is enforced and more than that many accounts carry this hash. */
    private boolean isOverShared(String hash, int maxAccountsPerHash) {
        if (maxAccountsPerHash <= 0) {
            return false;
        }
        int count = 0;
        for (Set<String> hs : hashes.values()) {
            if (hs.contains(hash)) {
                count++;
                if (count > maxAccountsPerHash) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Does {@code who} share an IP with {@code anchor} or anyone in {@code anchor}'s alt group? */
    public boolean sharesIpWithGroup(UUID who, UUID anchor) {
        return sharesIpWithGroup(who, anchor, 0);
    }

    /** Group-aware variant of {@link #sharesIp} with the same over-shared-hash cap as {@link #groupOf(UUID, int)}. */
    public boolean sharesIpWithGroup(UUID who, UUID anchor, int maxAccountsPerHash) {
        if (who == null || anchor == null || who.equals(anchor)) {
            return false;
        }
        // Direct link, or a link to any account already tied to the anchor.
        if (sharesIp(who, anchor)) {
            return true;
        }
        for (UUID member : groupOf(anchor, maxAccountsPerHash)) {
            if (sharesIp(who, member)) {
                return true;
            }
        }
        return false;
    }

    // ── Persistence ────────────────────────────────────────────────────────────

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
                logger.warning("Ignoring malformed player uuid in players.yml: " + key);
                continue;
            }
            List<String> hs = players.getStringList(key + ".hashes");
            if (!hs.isEmpty()) {
                hashes.put(uuid, ConcurrentHashMap.newKeySet());
                hashes.get(uuid).addAll(hs);
            }
            long last = players.getLong(key + ".last-login", 0L);
            if (last > 0) {
                lastLogin.put(uuid, last);
            }
        }
    }

    public YamlConfiguration buildSnapshotYaml() {
        YamlConfiguration out = new YamlConfiguration();
        // Union of both maps' keys: a player might have a last-login but no hash (address
        // was unavailable), and we want to keep that record either way.
        Set<UUID> all = new HashSet<>();
        all.addAll(hashes.keySet());
        all.addAll(lastLogin.keySet());
        for (UUID uuid : all) {
            Set<String> hs = hashes.get(uuid);
            if (hs != null && !hs.isEmpty()) {
                out.set("players." + uuid + ".hashes", new ArrayList<>(hs));
            }
            Long last = lastLogin.get(uuid);
            if (last != null) {
                out.set("players." + uuid + ".last-login", last);
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
