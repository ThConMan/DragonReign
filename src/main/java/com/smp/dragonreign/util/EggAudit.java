package com.smp.dragonreign.util;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.model.EggLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A read-only census of every dragon egg the server can currently see.
 *
 * <p>The plugin is built on the premise that exactly one egg exists, and until now nothing
 * ever checked. Several systems <em>create</em> an egg (respawn, give-back, the phantom-loss
 * watchdog, /giveegg) and none of them could tell you afterwards whether the total was still
 * one. This is that check.
 *
 * <p>It deliberately only counts, never removes. Deleting the wrong egg is unrecoverable —
 * there is no way to tell the "real" egg from a copy, because they are the same item — so
 * removal stays a human decision made with this report in hand.
 *
 * <p><b>What it cannot see</b>, and why the report says so out loud: offline players'
 * inventories and ender chests, and eggs sitting in chests, shulkers or item frames in
 * unloaded chunks. A clean report means "nothing wrong where I can look", not "nothing
 * wrong". Reading it as proof of a clean tournament would be exactly the false confidence
 * this is meant to remove.
 */
public final class EggAudit {

    /** One place an egg was found. */
    public record Finding(String where, int count) {}

    private final List<Finding> findings = new ArrayList<>();
    private int online;
    private int loose;
    private int placed;
    private int pending;
    private int enderchest;

    private EggAudit() {}

    public static EggAudit run(DragonReign plugin) {
        EggAudit audit = new EggAudit();
        audit.scan(plugin);
        return audit;
    }

    private void scan(DragonReign plugin) {
        // 1. The placed block, if the store thinks the egg is placed and the world is loaded.
        EggLocation loc = plugin.store().getLocation();
        if (loc != null) {
            Optional<Location> at = loc.toBukkit();
            if (at.isPresent() && at.get().getBlock().getType() == Material.DRAGON_EGG) {
                placed = 1;
                findings.add(new Finding("placed at " + describe(at.get()), 1));
            } else if (at.isPresent()) {
                // The store claims a placement that isn't there — worth surfacing on its own,
                // since it means the data file and the world already disagree.
                findings.add(new Finding("MISMATCH: store says placed at " + describe(at.get())
                        + " but no egg block is there", 0));
            }
        }

        // 2. Online players: person (inventory, offhand, cursor, bundles) and ender chest.
        for (Player player : Bukkit.getOnlinePlayers()) {
            int onPerson = Egg.countIn(player.getInventory());
            if (Egg.isDragonEgg(player.getItemOnCursor())) {
                onPerson += player.getItemOnCursor().getAmount();
            }
            if (onPerson > 0) {
                online += onPerson;
                findings.add(new Finding(player.getName() + " (carrying)", onPerson));
            }
            int inEnder = Egg.countIn(player.getEnderChest());
            if (inEnder > 0) {
                enderchest += inEnder;
                findings.add(new Finding(player.getName() + " (ender chest)", inEnder));
            }
        }

        // 3. Loose in the world: dropped items and falling blocks, across loaded worlds only.
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                int n = item.getItemStack().getAmount();
                if (Egg.isDragonEgg(item.getItemStack())) {
                    loose += n;
                    findings.add(new Finding("loose item at " + describe(item.getLocation()), n));
                }
            }
            for (FallingBlock fb : world.getEntitiesByClass(FallingBlock.class)) {
                if (fb.getBlockData().getMaterial() == Material.DRAGON_EGG) {
                    loose += 1;
                    findings.add(new Finding("falling egg at " + describe(fb.getLocation()), 1));
                }
            }
        }

        // 4. Owed back through a death — real eggs that exist only as a ledger entry.
        for (Map.Entry<UUID, Integer> e : plugin.store().pendingGiveView().entrySet()) {
            int n = e.getValue() != null ? e.getValue() : 0;
            if (n <= 0) {
                continue;
            }
            pending += n;
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            findings.add(new Finding((name != null ? name : e.getKey().toString())
                    + " (owed back after death)", n));
        }
    }

    private static String describe(Location l) {
        return (l.getWorld() != null ? l.getWorld().getName() : "?")
                + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    public int total() {
        return online + enderchest + loose + placed + pending;
    }

    public List<Finding> findings() {
        return findings;
    }

    /** Total eggs beyond the one that is supposed to exist. Negative means none were found. */
    public int surplus() {
        return total() - 1;
    }

    public String headline() {
        int t = total();
        if (t == 1) {
            return "OK — exactly one egg found.";
        }
        if (t == 0) {
            return "NONE FOUND — no egg is visible anywhere the audit can reach.";
        }
        return "DUPLICATES — " + t + " eggs found (" + (t - 1) + " more than there should be).";
    }
}
