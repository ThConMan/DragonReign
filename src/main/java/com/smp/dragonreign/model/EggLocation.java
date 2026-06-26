package com.smp.dragonreign.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the egg currently sits as a block. We keep both the world UUID (the
 * authoritative handle) and the world name (so the history GUI can still show
 * "world_the_end" even when that world isn't loaded right now).
 */
public final class EggLocation {

    private final UUID worldUuid;
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;

    public EggLocation(UUID worldUuid, String worldName, int x, int y, int z) {
        this.worldUuid = worldUuid;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static EggLocation of(Block block) {
        World w = block.getWorld();
        return new EggLocation(w.getUID(), w.getName(), block.getX(), block.getY(), block.getZ());
    }

    public static EggLocation of(Location loc) {
        World w = loc.getWorld();
        UUID id = w != null ? w.getUID() : null;
        String name = w != null ? w.getName() : "?";
        return new EggLocation(id, name, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** Resolve to a live Bukkit location, or empty if the world isn't loaded. */
    public Optional<Location> toBukkit() {
        World w = worldUuid != null ? Bukkit.getWorld(worldUuid) : null;
        if (w == null) {
            w = Bukkit.getWorld(worldName);
        }
        if (w == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(w, x, y, z));
    }

    public String worldName() {
        return worldName;
    }

    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    /** True if both refer to the same block (same world + x,y,z). Null-safe. */
    public boolean sameBlock(EggLocation other) {
        if (other == null) {
            return false;
        }
        if (x != other.x || y != other.y || z != other.z) {
            return false;
        }
        if (worldUuid != null && other.worldUuid != null) {
            return worldUuid.equals(other.worldUuid);
        }
        return worldName != null && worldName.equals(other.worldName);
    }

    /** Compact "world:x,y,z" form used in the history log. */
    public String compact() {
        return worldName + ":" + x + "," + y + "," + z;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world-uuid", worldUuid != null ? worldUuid.toString() : null);
        m.put("world-name", worldName);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    /** Rebuild from the expanded data.yml map; null if the map is unusable. */
    public static EggLocation deserialize(Map<?, ?> m) {
        if (m == null) {
            return null;
        }
        try {
            Object rawUuid = m.get("world-uuid");
            UUID id = rawUuid != null ? UUID.fromString(rawUuid.toString()) : null;
            String name = m.get("world-name") != null ? m.get("world-name").toString() : "?";
            int x = ((Number) m.get("x")).intValue();
            int y = ((Number) m.get("y")).intValue();
            int z = ((Number) m.get("z")).intValue();
            return new EggLocation(id, name, x, y, z);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Parse the compact "world:x,y,z" form back into a location (best-effort). */
    public static EggLocation fromCompact(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) {
            return null;
        }
        try {
            int colon = s.lastIndexOf(':');
            String world = s.substring(0, colon);
            String[] parts = s.substring(colon + 1).split(",");
            return new EggLocation(null, world,
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String toString() {
        return compact();
    }
}
