package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;
import java.util.UUID;

/**
 * Shows nearby players an action-bar pointer toward the placed egg, so a hidden egg can
 * always be hunted down. It is built to be cheap: it does nothing at all unless the egg
 * is placed and claimed, only ever looks at players in the egg's world, and filters by
 * squared distance before doing any direction maths. It never uses movement events.
 */
public final class CompassTask extends BukkitRunnable {

    // Eight arrows, clockwise from "dead ahead" (up).
    private static final String[] ARROWS = {"↑", "↗", "→", "↘",
            "↓", "↙", "←", "↖"};

    // The whole output set is tiny and fixed (1 outer band + 8 mid + 8 near = 17), so the
    // Components are deserialized once at class-load and reused — no MiniMessage parse or
    // string concat per nearby player per interval.
    private static final Component OUTER =
            Msg.mm("<dark_gray>· <gray>something's near ·</gray></dark_gray>");
    private static final Component[] MID = new Component[ARROWS.length];
    private static final Component[] NEAR = new Component[ARROWS.length];

    static {
        for (int i = 0; i < ARROWS.length; i++) {
            String arrow = ARROWS[i];
            MID[i] = Msg.mm("<gray>" + arrow + " <white>the egg is this way</white> " + arrow + "</gray>");
            NEAR[i] = Msg.mm("<gold>" + arrow + " <yellow>the egg is right here!</yellow> " + arrow + "</gold>");
        }
    }

    private final DragonReign plugin;

    public CompassTask(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ConfigManager c = plugin.config();
        if (!c.isCompassEnabled()) {
            return;
        }
        // Needs a placed AND claimed egg: an unclaimed egg sitting on the fountain
        // shouldn't be advertised, and a carried egg has no block to point at.
        EggLocation loc = plugin.store().getLocation();
        UUID owner = plugin.store().getOwner();
        if (loc == null || owner == null) {
            return;
        }
        Optional<Location> maybe = loc.toBukkit();
        if (maybe.isEmpty()) {
            return; // egg's world isn't loaded
        }
        Location egg = maybe.get();
        World world = egg.getWorld();
        if (world == null) {
            return;
        }

        int radius = c.getCompassRadius();
        double radiusSq = (double) radius * radius;
        boolean showToOwner = c.isCompassShowToOwner();

        // Only iterate the egg's world; gate on squared distance before any trig.
        for (Player p : world.getPlayers()) {
            if (!showToOwner && p.getUniqueId().equals(owner)) {
                continue;
            }
            Location pl = p.getLocation();
            double dx = (egg.getX() + 0.5) - pl.getX();
            double dz = (egg.getZ() + 0.5) - pl.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            Msg.nudge(p, band(p, dx, dz, Math.sqrt(distSq), radius));
        }
    }

    /**
     * Warmer/colder bands, returning a pre-built Component. Outer third: a faint
     * "something's near" with no arrow. Middle: a directional arrow. Last few blocks: the
     * arrow plus a "very close" marker.
     */
    private Component band(Player player, double dx, double dz, double dist, int radius) {
        double outerEdge = radius * (2.0 / 3.0);
        if (dist > outerEdge) {
            return OUTER;
        }
        int arrow = arrowIndex(player, dx, dz);
        return dist <= 3.0 ? NEAR[arrow] : MID[arrow];
    }

    /** Index of one of eight arrows from the bearing to the egg relative to the player's facing. */
    private int arrowIndex(Player player, double dx, double dz) {
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = bearing - player.getLocation().getYaw();
        rel = ((rel % 360) + 360) % 360;
        return ((int) Math.round(rel / 45.0)) & 7;
    }
}
