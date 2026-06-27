package com.smp.dragonreign.listener;

import com.smp.dragonreign.DragonReign;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Pops a small burst of enderman-style particles off whatever a Dragonlord hits — a melee
 * swing or their own arrow landing — so their strikes read as charged with the egg's power.
 *
 * <p>Deliberately lightweight: it only fires for victors (with their cosmetics on), spawns a
 * small, configurable count ({@code victor.hit-count}, default 10), and runs at MONITOR
 * priority ignoring cancelled events, so it never touches damage logic and adds nothing for
 * normal players. Lag matters more than spectacle here, so the burst is kept tiny.
 */
public final class CombatParticleListener implements Listener {

    private final DragonReign plugin;

    public CombatParticleListener(DragonReign plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        Entity victim = event.getEntity();
        // Mark combat for any victor involved (dealing OR taking damage) so the aura reacts —
        // independent of whether the hit-particle burst is enabled.
        if (attacker != null && plugin.victors().isVictor(attacker)) {
            plugin.markCombat(attacker.getUniqueId());
        }
        if (victim instanceof Player vp && plugin.victors().isVictor(vp)) {
            plugin.markCombat(vp.getUniqueId());
        }

        if (!plugin.config().isVictorHitEnabled()) {
            return;
        }
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (!plugin.victors().isVictor(attacker)
                || !plugin.victors().particleEnabled(attacker.getUniqueId())) {
            return;
        }

        Particle particle = parseParticle(plugin.config().getVictorHitParticle());
        // Centre the burst on the victim's body, with a small spread so it puffs out of the hit.
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        victim.getWorld().spawnParticle(particle, at, plugin.config().getVictorHitCount(),
                0.3, 0.4, 0.3, 0.02);
    }

    /** The player behind the hit: the attacker themselves, or the shooter of a projectile. */
    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private Particle parseParticle(String name) {
        if (name != null) {
            try {
                return Particle.valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return Particle.PORTAL;
    }
}
