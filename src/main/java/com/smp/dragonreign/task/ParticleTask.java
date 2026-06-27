package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Spins a small particle aura around online Dragonlords who have their aura turned on.
 * Runs on an interval (not every tick) and early-returns when the feature is off.
 *
 * <p>The per-interval scan over online players is intentional: victors can be granted by
 * the {@code dragonreign.victor} permission as well as the in-memory earned set, so there
 * is no cheap "are any victors online?" signal to short-circuit on. The set-membership
 * check inside {@link com.smp.dragonreign.victor.VictorManager#isVictor(org.bukkit.entity.Player)}
 * short-circuits for earned victors, and the work is one permission lookup per non-victor
 * every {@code victor.particle-interval-ticks} (default 2s) — negligible at normal counts.
 */
public final class ParticleTask extends BukkitRunnable {

    private final DragonReign plugin;

    public ParticleTask(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ConfigManager c = plugin.config();
        if (!c.isVictorParticleEnabled()) {
            return;
        }
        Particle particle = parseParticle(c.getVictorParticle());
        int density = c.getVictorParticleDensity();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.victors().isVictor(p)) {
                continue;
            }
            if (!plugin.victors().particleEnabled(p.getUniqueId())) {
                continue;
            }
            Location at = p.getLocation().add(0, 1.0, 0);
            // Small offsets give a gentle cloud hugging the player.
            p.getWorld().spawnParticle(particle, at, density, 0.4, 0.6, 0.4, 0.0);
        }
    }

    private Particle parseParticle(String name) {
        if (name != null) {
            try {
                return Particle.valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return Particle.HAPPY_VILLAGER;
    }
}
