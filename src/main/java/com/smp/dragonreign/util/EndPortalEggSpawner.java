package com.smp.dragonreign.util;

import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EggLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Places a fresh DRAGON_EGG on top of the End exit portal's bedrock fountain,
 * exactly as a first dragon kill would. Everything here is main-thread only.
 */
public final class EndPortalEggSpawner {

    private final ConfigManager config;
    private final Logger logger;

    public EndPortalEggSpawner(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /** The auto-detected (or configured) End world, or empty if none is loaded. */
    public Optional<World> findEndWorld() {
        String configured = config.getEndWorldName();
        if (configured != null && !configured.isBlank()) {
            World w = Bukkit.getWorld(configured);
            if (w != null) {
                return Optional.of(w);
            }
            logger.warning("Configured end-world-name '" + configured + "' isn't loaded; falling back to auto-detect.");
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.THE_END) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    /**
     * Spawn the egg on the fountain apex. Returns where it landed, or empty if the
     * End world isn't available (caller logs/continues gracefully).
     */
    public Optional<EggLocation> spawnAtExitPortal() {
        Optional<World> maybeWorld = findEndWorld();
        if (maybeWorld.isEmpty()) {
            logger.warning("No THE_END world loaded — skipping egg spawn step.");
            return Optional.empty();
        }
        World end = maybeWorld.get();

        Block target = locateFountainApex(end);
        if (target == null) {
            logger.warning("Could not locate the End fountain; egg not spawned.");
            return Optional.empty();
        }

        // Idempotent-ish: if an egg is already sitting there, just report it.
        if (target.getType() != Material.DRAGON_EGG) {
            target.setType(Material.DRAGON_EGG, false);
        }
        return Optional.of(EggLocation.of(target));
    }

    /**
     * Find the AIR block directly above the fountain's topmost bedrock at the
     * central column. Tries the DragonBattle portal reference first, then scans.
     */
    private Block locateFountainApex(World end) {
        int cx = 0;
        int cz = 0;

        DragonBattle battle = end.getEnderDragonBattle();
        if (battle != null) {
            Location portal = battle.getEndPortalLocation();
            if (portal != null) {
                cx = portal.getBlockX();
                cz = portal.getBlockZ();
            }
        }

        // Make sure the column is loaded before poking at blocks.
        end.getChunkAt(cx >> 4, cz >> 4).load(true);

        // Scan down for the fountain tip (topmost bedrock) and sit on the air above it.
        int top = Math.min(end.getMaxHeight() - 1, 100);
        int bottom = Math.max(end.getMinHeight(), 0);
        for (int y = top; y >= bottom; y--) {
            Block b = end.getBlockAt(cx, y, cz);
            if (b.getType() == Material.BEDROCK) {
                return end.getBlockAt(cx, y + 1, cz);
            }
        }

        // No fountain generated yet — fall back to the surface and warn.
        int surface = end.getHighestBlockYAt(cx, cz) + 1;
        logger.warning("No bedrock fountain found at (" + cx + "," + cz + "); placing egg at surface y=" + surface + ".");
        return end.getBlockAt(cx, surface, cz);
    }
}
