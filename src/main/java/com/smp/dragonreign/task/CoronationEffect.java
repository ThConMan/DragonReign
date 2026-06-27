package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * The one-time "coronation" flourish played when a player becomes a Dragonlord: the aura's
 * energy spirals inward and implodes on them, then blooms back outward in a ring with a roar.
 * A short, self-cancelling animation — fires once, costs nothing afterwards.
 */
public final class CoronationEffect {

    private static final int IMPLODE_TICKS = 16;
    private static final int BLOOM_TICKS = 16;

    private CoronationEffect() {
    }

    public static void play(DragonReign plugin, Player player) {
        final World world = player.getWorld();
        final Color color = parseColor(plugin.config().getVictorAuraColor());
        final Particle.DustOptions dust = new Particle.DustOptions(color, 1.3f);
        final Particle.DustOptions bright = new Particle.DustOptions(Color.fromRGB(0xEAD9FF), 1.5f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t > IMPLODE_TICKS + BLOOM_TICKS || !player.isOnline()) {
                    cancel();
                    return;
                }
                Location centre = player.getLocation().add(0, 1.0, 0);
                if (t < IMPLODE_TICKS) {
                    // Implode: a ring of motes spiralling inward and downward onto the player.
                    double p = t / (double) IMPLODE_TICKS;          // 0 → 1
                    double radius = 2.6 * (1.0 - p) + 0.2;
                    double y = centre.getY() + 1.4 * (1.0 - p);
                    int points = 26;
                    for (int i = 0; i < points; i++) {
                        double a = i * (Math.PI * 2 / points) + t * 0.45; // spin as it shrinks
                        double x = centre.getX() + radius * Math.cos(a);
                        double z = centre.getZ() + radius * Math.sin(a);
                        world.spawnParticle(Particle.DUST, new Location(world, x, y, z), 1, 0, 0, 0, 0, dust);
                    }
                } else {
                    int bt = t - IMPLODE_TICKS;
                    if (bt == 0) {
                        // The flash at the moment of coronation.
                        world.spawnParticle(Particle.DUST, centre, 70, 0.35, 0.7, 0.35, 0, bright);
                        world.playSound(centre, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.5f);
                        world.playSound(centre, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.8f);
                    }
                    // Bloom: an expanding ring sweeping outward and fading.
                    double p = bt / (double) BLOOM_TICKS;            // 0 → 1
                    double radius = 0.3 + p * 3.4;
                    float size = (float) (1.5 * (1.0 - p) + 0.3);
                    Particle.DustOptions ring = new Particle.DustOptions(color, size);
                    int points = 30;
                    for (int i = 0; i < points; i++) {
                        double a = i * (Math.PI * 2 / points);
                        double x = centre.getX() + radius * Math.cos(a);
                        double z = centre.getZ() + radius * Math.sin(a);
                        world.spawnParticle(Particle.DUST, new Location(world, x, centre.getY() - 0.7, z),
                                1, 0, 0, 0, 0, ring);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static Color parseColor(String hex) {
        if (hex != null) {
            try {
                return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return Color.fromRGB(0x9D4EDD);
    }
}
