package com.smp.dragonreign.task;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Renders the full Dragonlord aura around online victors who have it switched on. This is a
 * dense, layered, parametric presence modelled on the kind of effects particle plugins like
 * PlayerParticles ship (wings / vortex / spiral / orbit), but built specifically for the
 * Dragonlord and baked into DragonReign so it needs no external plugin:
 *
 * <ul>
 *   <li><b>Wandering spirits</b> — the showpiece: a swarm of trailing wisps that each drift
 *       their own open, non-repeating path through the space around the player.</li>
 *   <li><b>Engulfing vortex</b> — four strands spiralling up the body with a bulged radius,
 *       wrapping the player in a column of energy.</li>
 *   <li><b>Ground sigil</b> — twin Archimedean spiral arms sweeping across the floor.</li>
 *   <li><b>Embers</b> — small fire motes that rise and fade for a living shimmer.</li>
 *   <li><b>Dragon wings</b> — flapping wings behind the shoulders, available but OFF by
 *       default (overused); enable with {@code victor.wings-enabled}.</li>
 * </ul>
 *
 * <p><b>PvP / view safety.</b> A clear cylinder is kept around the player's own body at camera
 * height (radius {@link #CLEAR_RADIUS}, between {@link #CLEAR_LOW} and {@link #CLEAR_HIGH}): no
 * particle spawns inside it, so the aura wraps the player from a distance and orbits around them
 * but never fills their first-person view or hides their model. Ground sigil (below the column)
 * and embers (kept at the feet) still show; the spirits and vortex live out past the radius.
 *
 * <p>Refreshes several times a second (see {@code victor.particle-interval-ticks}, default 5).
 * Each layer can be toggled in config, and {@code victor.particle-density} scales the swirls.
 */
public final class ParticleTask extends BukkitRunnable {

    // ── Wings ────────────────────────────────────────────────────────────────────
    private static final int WING_BONES = 7;          // ribs radiating from each shoulder
    private static final int WING_POINTS = 8;          // membrane points along each bone
    private static final double WING_SPAN = 2.2;       // outward reach of the longest bone
    private static final double WING_SHOULDER_Y = 1.30;
    private static final double WING_BACK = 0.30;       // how far behind the back they anchor
    private static final double WING_BACK_SWEEP = 0.12; // extra backward curl per block of span
    private static final double WING_TOP_ANGLE = Math.toRadians(72);   // leading bone, up-and-out
    private static final double WING_BOTTOM_ANGLE = Math.toRadians(-30); // trailing bone, drooped
    private static final double WING_FLAP_BASE = Math.toRadians(-6);
    private static final double WING_FLAP_AMP = Math.toRadians(26);     // flap travel
    private static final double WING_FLAP_SPEED = 3.2;

    // ── Vortex ───────────────────────────────────────────────────────────────────
    private static final double VORTEX_TOP = 2.8;
    private static final int VORTEX_STRANDS = 3;
    private static final double VORTEX_TURNS = 2.5;
    private static final double VORTEX_MIN_R = 1.50;   // sits outside the clear column, not on the body
    private static final double VORTEX_BULGE = 0.50;   // added at mid-height for a swirling belly

    // ── Ground sigil ─────────────────────────────────────────────────────────────
    private static final double GROUND_Y = 0.06;
    private static final int GROUND_ARMS = 2;
    private static final double GROUND_MIN_R = 1.25;   // starts outside the clear column (open look-down)
    private static final double GROUND_MAX_R = 2.50;
    private static final double GROUND_SWIRLS = 2.2;   // how many turns each arm winds

    // ── Wandering spirits (the showpiece) ────────────────────────────────────────
    private static final double ORBIT_CENTER_Y = 1.05;
    private static final double ORBIT_SPEED = 2.0;      // base path-evolution speed (scaled per wisp)
    private static final double KNOT_R0 = 0.07;         // how far the sucking motes start out from the core
    private static final double KNOT_SPEED = 0.045;     // gentle suck/spill velocity (scaled up when surging)
    private static final int TRAIL_LEN = 3;             // short fading tail behind each ball
    private static final double TRAIL_GAP = 0.04;       // spacing between the tail samples

    // ── Livewire surges: occasional bursts where a wisp zips and crackles ─────────
    private static final double SURGE_FREQ = 2.7;       // how often a wisp surges
    private static final double SURGE_THRESHOLD = 0.62; // higher = surges are rarer/briefer
    private static final double SURGE_SPEED_BOOST = 3.5;// extra path speed at peak surge
    private static final double JITTER_AMP = 0.85;      // blocks of erratic dart at peak surge
    private static final double JFX = 11.0, JFZ = 13.0, JFY = 17.0; // mismatched crackle frequencies

    // ── Embers ───────────────────────────────────────────────────────────────────
    private static final double EMBER_MIN_Y = 0.15;
    private static final double EMBER_MAX_Y = 0.90;   // stays below the clear column — fire at the feet
    private static final double EMBER_RADIUS = 0.85;  // scattered close around the feet
    private static final double EMBER_RISE = 0.05;

    // ── Clear column (keeps the wearer's own camera/view free) ───────────────────
    // Nothing spawns inside this cylinder around the player's body at camera height, so the
    // aura stays out of their first-person view. The radius is config-driven (live-tunable via
    // victor.view-clear-radius) because it's the dial that trades "more aura" vs "clearer view".
    // The band spans chest to just above the head — eyes (~1.62) sit inside it. Ground (below
    // CLEAR_LOW) and overhead (above CLEAR_HIGH) and anything beyond the radius still show.
    private static final double CLEAR_LOW = 1.15;
    private static final double CLEAR_HIGH = 2.20;
    // A narrower column kept clear through the WHOLE body height (feet included), so looking
    // straight down/up while moving stays open — particles ring the player instead of stacking
    // directly under or over them.
    private static final double INNER_CLEAR = 1.20;
    private static final double INNER_CLEAR_SQ = INNER_CLEAR * INNER_CLEAR;

    // Small step + frequent emission (interval ~2) = smooth, flowing motion instead of the
    // jumpy "pulsing" you get from big steps emitted infrequently.
    private static final double SPIN_STEP = Math.toRadians(3);

    // How non-DUST particles (e.g. PORTAL) drift, set via victor.particle-motion.
    private static final int MOTION_STILL = 0;   // default in-place swirl
    private static final int MOTION_INWARD = 1;  // pulled toward the player (contained energy)
    private static final int MOTION_OUTWARD = 2; // pushed away from the player
    private static final int MOTION_UP = 3;      // rise upward
    private static final int MOTION_SWIRL = 4;   // whirl tangentially around the player

    // Lightning arcs between nearby orbiting balls (the livewire made literal).
    private static final double ARC_DIST = 1.8;  // only arc between balls this close
    private static final double ARC_PROB = 0.05; // chance per close pair per cycle — kept rare
    // Surge sound: how long a single surging ball must look before it can play a sound again.
    private static final long SURGE_SOUND_COOLDOWN_MS = 1400L;

    private final DragonReign plugin;
    /** Wrapped [0,2π) angle for the rotational layers (vortex/ground/wing flap). */
    private double spin;
    /** Unwrapped, ever-growing accumulator for spirit motion + surges (no 2π wrap glitch). */
    private double flow;
    /** Squared clear-column radius for the current cycle (set from config in run()). */
    private double clearRadiusSq;
    /** Non-dust particle drift mode + speed for the current cycle (set from config in run()). */
    private int motionMode;
    private double motionSpeed;
    /** Per-player cooldown so the surge sound doesn't machine-gun. */
    private final Map<UUID, Long> lastSurgeSound = new ConcurrentHashMap<>();

    public ParticleTask(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ConfigManager c = plugin.config();
        if (!c.isVictorParticleEnabled()) {
            return;
        }
        Particle ring = parseParticle(c.getVictorParticle(), Particle.PORTAL);
        int density = c.getVictorParticleDensity();
        boolean embersOn = c.isVictorEmberEnabled();
        Particle ember = embersOn ? parseParticle(c.getVictorEmberParticle(), Particle.SOUL_FIRE_FLAME) : null;
        Particle ember2 = embersOn ? parseParticle(c.getVictorEmberParticle2(), Particle.FLAME) : null;
        int emberCount = c.getVictorEmberCount();
        double livewire = c.getVictorLivewireIntensity();
        boolean wings = c.isVictorWingsEnabled();
        boolean vortex = c.isVictorVortexEnabled();
        boolean ground = c.isVictorGroundEnabled();
        boolean orbit = c.isVictorOrbitEnabled();
        int orbitCount = c.getVictorOrbitCount();
        double clearR = c.getVictorViewClearRadius();
        clearRadiusSq = clearR * clearR;
        motionMode = parseMotion(c.getVictorParticleMotion());
        motionSpeed = c.getVictorParticleMotionSpeed();
        // DUST types carry a colour + size. The ambient vortex/ground use a single uniform
        // mote; the spirits build their own per-wisp mote (varied size) inside spirits().
        Color from = parseColor(c.getVictorAuraColor());
        Color to = parseColor(c.getVictorAuraColor2());
        float spiritSize = (float) c.getVictorSpiritSize();
        float auraSize = (float) c.getVictorAuraSize();
        Object ambientData = dustData(ring, from, to, auraSize);
        boolean combatReactive = c.isVictorCombatReactive();
        boolean arcs = c.isVictorArcsEnabled();
        boolean surgeSound = c.isVictorSurgeSound();
        spin = (spin + SPIN_STEP) % (Math.PI * 2);
        flow += SPIN_STEP; // continuous (never wrapped) so spirit motion has no seam

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.victors().isVictor(p) || !plugin.victors().particleEnabled(p.getUniqueId())) {
                continue;
            }
            double agitation = combatReactive ? agitation(p) : 0.0;
            spawnAura(p, ring, from, to, spiritSize, ambientData, density, ember, ember2, emberCount,
                    livewire, agitation, arcs, surgeSound, wings, vortex, ground, orbit, orbitCount);
        }
    }

    /**
     * How worked-up the aura should be (0..1) based on what the holder is doing: sprinting,
     * hurt, or recently in combat. Drives more frequent/violent livewire surges.
     */
    private double agitation(Player p) {
        double a = 0.0;
        if (p.isSprinting()) {
            a += 0.35;
        }
        double max = p.getMaxHealth();
        double frac = max > 0 ? p.getHealth() / max : 1.0;
        if (frac < 0.5) {
            a += (0.5 - frac) * 1.4; // ramps to +0.7 near death
        }
        long age = plugin.combatAgeMillis(p.getUniqueId());
        if (age < 5000L) {
            a += 0.7 * (1.0 - age / 5000.0); // decays over 5s since the last hit
        }
        return Math.min(1.0, a);
    }

    private void spawnAura(Player p, Particle ring, Color from, Color to, float spiritSize,
                           Object ambientData, int density, Particle ember, Particle ember2, int emberCount,
                           double livewire, double agitation, boolean arcs, boolean surgeSound,
                           boolean wings, boolean vortex, boolean ground, boolean orbit, int orbitCount) {
        Location loc = p.getLocation();
        World world = p.getWorld();
        double cx = loc.getX();
        double cy = loc.getY();
        double cz = loc.getZ();
        // Forward + right horizontal unit vectors from the player's yaw (Bukkit frame).
        double yawRad = Math.toRadians(loc.getYaw());
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double rightX = -fwdZ;
        double rightZ = fwdX;
        double faceAngle = Math.atan2(fwdZ, fwdX);

        if (ground) {
            groundSigil(world, ring, ambientData, cx, cy, cz, faceAngle, density);
        }
        if (vortex) {
            vortex(world, ring, ambientData, cx, cy, cz, faceAngle, density);
        }
        if (orbit && orbitCount > 0) {
            double maxSurge = spirits(world, ring, from, to, spiritSize, livewire, agitation, arcs,
                    cx, cy, cz, faceAngle, orbitCount);
            if (surgeSound && maxSurge > 0.6) {
                playSurgeSound(p);
            }
        }
        if (wings) {
            wings(world, ring, ambientData, cx, cy, cz, fwdX, fwdZ, rightX, rightZ, faceAngle);
        }
        if (ember != null && emberCount > 0) {
            embers(world, ember, ember2, cx, cy, cz, emberCount);
        }
    }

    /** A soft electric crackle when a ball surges, rate-limited per player so it stays subtle. */
    private void playSurgeSound(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastSurgeSound.get(p.getUniqueId());
        if (last != null && now - last < SURGE_SOUND_COOLDOWN_MS) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > 0.5) {
            return; // not every eligible cycle, so it stays occasional
        }
        lastSurgeSound.put(p.getUniqueId(), now);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.22f, 1.6f);
    }

    /**
     * Two flapping dragon wings behind the player. Each wing is a fan of {@code WING_BONES}
     * bones from the shoulder, swept from a high leading edge down to a drooped trailing edge;
     * membrane points run along each bone and the bones lengthen toward the middle for a
     * dragon silhouette. The whole fan rocks up and down over time to flap, and the trailing
     * tips are joined so the wing reads as a membrane rather than loose lines.
     */
    private void wings(World world, Particle particle, Object data, double cx, double cy, double cz,
                       double fwdX, double fwdZ, double rightX, double rightZ, double faceAngle) {
        double flap = WING_FLAP_BASE + WING_FLAP_AMP * Math.sin(spin * WING_FLAP_SPEED);
        for (int side = -1; side <= 1; side += 2) {
            double[] tipOut = new double[WING_BONES];
            double[] tipUp = new double[WING_BONES];
            for (int b = 0; b < WING_BONES; b++) {
                double f = b / (double) (WING_BONES - 1);
                double boneAngle = WING_TOP_ANGLE + (WING_BOTTOM_ANGLE - WING_TOP_ANGLE) * f + flap;
                // Bones are longest in the upper-middle of the wing, shorter at top and bottom.
                double lengthScale = 0.55 + 0.45 * Math.sin(Math.PI * (0.15 + 0.85 * f));
                double boneLen = WING_SPAN * lengthScale;
                for (int s = 1; s <= WING_POINTS; s++) {
                    double d = boneLen * (s / (double) WING_POINTS);
                    double out = d * Math.cos(boneAngle);
                    double up = d * Math.sin(boneAngle);
                    placeWing(world, particle, data, cx, cy, cz, fwdX, fwdZ, rightX, rightZ,
                            faceAngle, side, out, up, d);
                    if (s == WING_POINTS) {
                        tipOut[b] = out;
                        tipUp[b] = up;
                    }
                }
            }
            // Trailing edge: stitch the bone tips together so the wing has a defined edge.
            for (int b = 0; b < WING_BONES - 1; b++) {
                for (int j = 1; j <= 2; j++) {
                    double t = j / 3.0;
                    double out = tipOut[b] + (tipOut[b + 1] - tipOut[b]) * t;
                    double up = tipUp[b] + (tipUp[b + 1] - tipUp[b]) * t;
                    placeWing(world, particle, data, cx, cy, cz, fwdX, fwdZ, rightX, rightZ,
                            faceAngle, side, out, up, WING_SPAN);
                }
            }
        }
    }

    /** Map a wing-local (out, up) point to world space behind the player and spawn it. */
    private void placeWing(World world, Particle particle, Object data, double cx, double cy, double cz,
                           double fwdX, double fwdZ, double rightX, double rightZ,
                           double faceAngle, int side, double out, double up, double span) {
        double back = WING_BACK + WING_BACK_SWEEP * span;
        double x = cx + rightX * (side * out) - fwdX * back;
        double z = cz + rightZ * (side * out) - fwdZ * back;
        double y = cy + WING_SHOULDER_Y + up;
        spawn(world, particle, data, cx, cy, cz, x, y, z, faceAngle);
    }

    /** Rising vortex: strands spiralling up the body, bulged at mid-height to engulf it. */
    private void vortex(World world, Particle particle, Object data, double cx, double cy, double cz,
                        double faceAngle, int density) {
        int steps = Math.max(18, (int) (density * 1.6));
        for (int s = 0; s < VORTEX_STRANDS; s++) {
            double strandOffset = (Math.PI * 2 / VORTEX_STRANDS) * s;
            for (int i = 0; i < steps; i++) {
                double t = i / (double) (steps - 1);
                double y = cy + t * VORTEX_TOP;
                double radius = VORTEX_MIN_R + VORTEX_BULGE * Math.sin(Math.PI * t);
                double angle = spin + strandOffset + t * VORTEX_TURNS * Math.PI * 2;
                double x = cx + radius * Math.cos(angle);
                double z = cz + radius * Math.sin(angle);
                spawn(world, particle, data, cx, cy, cz, x, y, z, faceAngle);
            }
        }
    }

    /** Twin Archimedean spiral arms sweeping the floor, rotating as a sigil. */
    private void groundSigil(World world, Particle particle, Object data, double cx, double cy, double cz,
                             double faceAngle, int density) {
        int steps = Math.max(20, density * 2);
        for (int a = 0; a < GROUND_ARMS; a++) {
            double armOffset = (Math.PI * 2 / GROUND_ARMS) * a;
            for (int i = 0; i < steps; i++) {
                double t = i / (double) (steps - 1);
                double radius = GROUND_MIN_R + t * (GROUND_MAX_R - GROUND_MIN_R);
                double angle = spin + armOffset + t * GROUND_SWIRLS * Math.PI * 2;
                double x = cx + radius * Math.cos(angle);
                double z = cz + radius * Math.sin(angle);
                spawn(world, particle, data, cx, cy, cz, x, cy + GROUND_Y, z, faceAngle);
            }
        }
    }

    /**
     * A swarm of spirit-wisps drifting around the player. Each wisp moves on three sine waves
     * (x, z, y) at its own mismatched, non-integer frequencies, so it traces an open path that
     * never settles into a loop — it wanders. Reach and vertical centre are spread out (derived
     * from the index, so motion stays smooth), so some hug close while others swing wide.
     *
     * <p>Each wisp also has a per-index <b>weight</b> {@code w} that gives the swarm variety:
     * heavy wisps (w→1) drift slower, with a longer, tighter, larger-mote trail — dense glowing
     * orbs — while light wisps (w→0) are faster, sparser and smaller. No two read the same.
     */
    private double spirits(World world, Particle particle, Color from, Color to, float baseSize,
                           double livewire, double agitation, boolean arcs,
                           double cx, double cy, double cz, double faceAngle, int count) {
        double[] bx = new double[count], by = new double[count], bz = new double[count];
        boolean[] visible = new boolean[count];
        double maxSurge = 0.0;
        // Combat reactivity: agitation makes surges both stronger and far more frequent.
        double effLivewire = livewire * (1.0 + 1.6 * agitation);
        double effThreshold = Math.max(0.05, SURGE_THRESHOLD - 0.45 * agitation);

        for (int s = 0; s < count; s++) {
            double w = hash(s, 1.0);                       // 0 = light/small/fast, 1 = heavy/big/slow
            double speedFactor = 1.40 - 0.95 * w;          // some wisps fast, some slow
            int ballCount = 3 + (int) Math.round(5 * w);   // tight knot — only a few motes per ball
            float size = (float) (baseSize * (0.75 + 0.75 * w));
            Object data = dustData(particle, from, to, size);

            // Livewire surge: a staggered, occasional burst where this ball suddenly zips and
            // sprays — aggressive — then settles. surge is 0 most of the time, peaking rarely.
            double surgePhase = hash(s, 3.0) * Math.PI * 2;
            double surgeRaw = Math.sin(flow * SURGE_FREQ + surgePhase);
            double surge = Math.max(0.0, (surgeRaw - effThreshold) / (1.0 - effThreshold)) * effLivewire;
            surge = Math.min(surge, 1.5);
            maxSurge = Math.max(maxSurge, surge);
            double jitter = JITTER_AMP * surge;
            int motes = (int) Math.round(ballCount * (1.0 + 0.8 * surge));  // denser when surging
            double knotSpeed = KNOT_SPEED * (1.0 + 3.0 * surge);           // suck/spill harder when surging
            double r0 = KNOT_R0 * (1.0 + 1.2 * surge);

            double fx = 0.70 + 0.123 * s;   // mismatched, non-integer frequencies per axis
            double fz = 0.91 + 0.157 * s;
            double fy = 0.53 + 0.187 * s;
            double phx = 0.7 * s, phz = 2.3 * s, phy = 1.1 * s;
            double hx = 2.90 + 0.60 * Math.sin(s * 1.7);   // out past the clear column so they're visible around you
            double hz = 2.90 + 0.60 * Math.cos(s * 2.1);
            double centreY = ORBIT_CENTER_Y + 0.65 * Math.sin(s * 0.9);
            double vamp = 1.10 + 0.30 * Math.sin(s * 1.3);
            double headTime = flow * ORBIT_SPEED * speedFactor * (1.0 + SURGE_SPEED_BOOST * surge);
            // Head = a tight knot that sucks and spills; behind it a short fading tail.
            for (int k = 0; k <= TRAIL_LEN; k++) {
                double time = headTime - k * TRAIL_GAP;
                double x = cx + hx * Math.sin(fx * time + phx) + jitter * Math.sin(time * JFX + s * 1.3);
                double z = cz + hz * Math.sin(fz * time + phz) + jitter * Math.sin(time * JFZ + s * 2.7);
                double y = cy + centreY + vamp * Math.sin(fy * time + phy) + jitter * Math.sin(time * JFY + s * 0.9);
                if (k == 0) {
                    bx[s] = x; by[s] = y; bz[s] = z;
                    visible[s] = !inClear(x, y, z, cx, cy, cz);
                    spawnKnot(world, particle, data, cx, cy, cz, x, y, z, motes, r0, knotSpeed, s);
                } else {
                    // Fading tail: fewer, near-static motes the older the sample.
                    double fade = 1.0 - (double) k / (TRAIL_LEN + 1);
                    int tm = Math.max(1, (int) Math.round(motes * 0.45 * fade));
                    spawnBall(world, particle, data, cx, cy, cz, x, y, z, tm, 0.03, 0.0);
                }
            }
        }
        if (arcs) {
            drawArcs(world, cx, cy, cz, bx, by, bz, visible, count);
        }
        return maxSurge;
    }

    /** Occasional purple lightning arcs crackling between two balls that drift close together. */
    private void drawArcs(World world, double cx, double cy, double cz,
                          double[] xs, double[] ys, double[] zs, boolean[] vis, int count) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Particle.DustOptions spark = new Particle.DustOptions(Color.fromRGB(0xE6D2FF), 0.7f);
        for (int i = 0; i < count; i++) {
            if (!vis[i]) {
                continue;
            }
            for (int j = i + 1; j < count; j++) {
                if (!vis[j]) {
                    continue;
                }
                double dx = xs[i] - xs[j], dy = ys[i] - ys[j], dz = zs[i] - zs[j];
                if (dx * dx + dy * dy + dz * dz > ARC_DIST * ARC_DIST) {
                    continue;
                }
                if (rng.nextDouble() > ARC_PROB) {
                    continue;
                }
                arc(world, spark, xs[i], ys[i], zs[i], xs[j], ys[j], zs[j], rng);
            }
        }
    }

    /** A single jagged lightning bolt of bright motes between two points. */
    private void arc(World world, Particle.DustOptions spark, double ax, double ay, double az,
                     double bx, double by, double bz, ThreadLocalRandom rng) {
        int seg = 8;
        for (int i = 0; i <= seg; i++) {
            double t = i / (double) seg;
            double env = 0.20 * Math.sin(Math.PI * t); // jitter envelope — zero at the endpoints
            double x = ax + (bx - ax) * t + (rng.nextDouble() * 2 - 1) * env;
            double y = ay + (by - ay) * t + (rng.nextDouble() * 2 - 1) * env;
            double z = az + (bz - az) * t + (rng.nextDouble() * 2 - 1) * env;
            world.spawnParticle(Particle.DUST, new Location(world, x, y, z), 1, 0, 0, 0, 0, spark);
        }
    }

    /** True if a point falls inside either clear zone (used to cull and to gate arcs). */
    private boolean inClear(double x, double y, double z, double cx, double cy, double cz) {
        double relY = y - cy;
        double dx = x - cx, dz = z - cz;
        double d2 = dx * dx + dz * dz;
        if (relY >= CLEAR_LOW && relY <= CLEAR_HIGH && d2 < clearRadiusSq) {
            return true;
        }
        return relY <= CLEAR_HIGH && d2 < INNER_CLEAR_SQ;
    }

    /**
     * A tight knot that sucks and spills at once: half the motes spawn at the knot's edge and
     * fly inward (sucking), half spawn at the core and fly outward (spilling). The motes are
     * placed on an even (golden-angle) sphere and given a gentle velocity, so the ball stays a
     * compact knot that visibly breathes/implodes/overflows. Honours the clear-column cull.
     */
    private void spawnKnot(World world, Particle particle, Object data, double cx, double cy, double cz,
                           double x, double y, double z, int motes, double r0, double speed, int seed) {
        if (inClear(x, y, z, cx, cy, cz)) {
            return;
        }
        for (int i = 0; i < motes; i++) {
            double a = (i + seed) * 2.399963;              // golden angle → even spread on the sphere
            double uy = 1.0 - (i + 0.5) / motes * 2.0;     // -1..1
            double rr = Math.sqrt(Math.max(0.0, 1.0 - uy * uy));
            double ux = Math.cos(a) * rr, uz = Math.sin(a) * rr; // unit direction
            boolean suck = (i % 2 == 0);
            double px, py, pz, vx, vy, vz;
            if (suck) {                                    // start at the edge, fly inward
                px = x + ux * r0; py = y + uy * r0; pz = z + uz * r0;
                vx = -ux; vy = -uy; vz = -uz;
            } else {                                       // start at the core, fly outward
                px = x; py = y; pz = z;
                vx = ux; vy = uy; vz = uz;
            }
            Location at = new Location(world, px, py, pz);
            if (data != null) {
                world.spawnParticle(particle, at, 0, vx, vy, vz, speed, data);
            } else {
                world.spawnParticle(particle, at, 0, vx, vy, vz, speed);
            }
        }
    }

    /**
     * Spawn one spirit as a condensed, overflowing ball: a tight cluster of particles whose
     * spread pulls them into a swirling sphere (the enderman look) that spills out a little
     * (more when surging). Honours the clear-column cull on the ball's centre.
     */
    private void spawnBall(World world, Particle particle, Object data, double cx, double cy, double cz,
                           double x, double y, double z, int motes, double radius, double overflow) {
        if (inClear(x, y, z, cx, cy, cz)) {
            return;
        }
        Location at = new Location(world, x, y, z);
        if (data != null) {
            world.spawnParticle(particle, at, motes, radius, radius, radius, 0.0, data);
        } else {
            // count>0 + spread: PORTAL packs into a swirling ball; overflow (speed) makes it spill.
            world.spawnParticle(particle, at, motes, radius, radius, radius, overflow);
        }
    }

    /** Build the DUST/DUST_COLOR_TRANSITION render data for a mote of the given size; null otherwise. */
    private Object dustData(Particle particle, Color from, Color to, float size) {
        if (particle == Particle.DUST) {
            return new Particle.DustOptions(from, size);
        }
        if (particle == Particle.DUST_COLOR_TRANSITION) {
            return new Particle.DustTransition(from, to, size);
        }
        return null;
    }

    /** Deterministic pseudo-random value in [0,1) from a wisp index + seed (stable per index). */
    private static double hash(int s, double seed) {
        double v = Math.sin((s + 1) * 12.9898 + seed * 78.233) * 43758.5453;
        return v - Math.floor(v);
    }

    /**
     * Scatter a few embers close around the feet, each drifting up to fade. Every ember randomly
     * picks one of the two fire types so blue soul-fire and red fire mix together.
     */
    private void embers(World world, Particle ember, Particle ember2, double cx, double cy, double cz, int count) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double angle = rng.nextDouble(Math.PI * 2);
            double radius = rng.nextDouble(EMBER_RADIUS);
            double x = cx + radius * Math.cos(angle);
            double z = cz + radius * Math.sin(angle);
            double y = cy + rng.nextDouble(EMBER_MIN_Y, EMBER_MAX_Y);
            Particle fire = (ember2 != null && rng.nextBoolean()) ? ember2 : ember;
            // count 0 makes the offset act as a velocity: a gentle upward rise so it floats and fades.
            world.spawnParticle(fire, new Location(world, x, y, z), 0, 0.0, EMBER_RISE, 0.0, 1.0);
        }
    }

    /**
     * Spawn a single particle at an exact point, unless it falls in the forward eye-line
     * guard (head-height band, wedge in front of the player's view) — then it is skipped.
     */
    private void spawn(World world, Particle particle, Object data, double cx, double cy, double cz,
                       double x, double y, double z, double faceAngle) {
        if (inClear(x, y, z, cx, cy, cz)) {
            return; // keep the player's own view column clear
        }
        Location at = new Location(world, x, y, z);
        if (data != null) {
            // DUST/DUST_COLOR_TRANSITION: place one exact, sized, coloured mote.
            world.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0, data);
            return;
        }
        if (motionMode == MOTION_STILL || motionSpeed <= 0.0) {
            // Default: a small spawn spread makes PORTAL streak in place (the enderman swirl).
            world.spawnParticle(particle, at, 1, 0.28, 0.28, 0.28, 0.0);
            return;
        }
        // Controlled drift: count 0 makes the offset act as a velocity direction, scaled by speed.
        double vx = 0, vy = 0, vz = 0;
        switch (motionMode) {
            case MOTION_UP -> vy = 1;
            case MOTION_INWARD, MOTION_OUTWARD -> {
                double ddx = cx - x, ddy = (cy + 1.0) - y, ddz = cz - z; // toward the player's core
                double len = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                if (len > 1e-6) {
                    double sgn = motionMode == MOTION_INWARD ? 1.0 : -1.0;
                    vx = sgn * ddx / len; vy = sgn * ddy / len; vz = sgn * ddz / len;
                }
            }
            case MOTION_SWIRL -> {
                double ddx = x - cx, ddz = z - cz; // tangent = perpendicular to the radius, horizontal
                double len = Math.sqrt(ddx * ddx + ddz * ddz);
                if (len > 1e-6) { vx = -ddz / len; vz = ddx / len; }
            }
            default -> { }
        }
        world.spawnParticle(particle, at, 0, vx, vy, vz, motionSpeed);
    }

    /** Map the configured motion name to one of the MOTION_* constants. */
    private int parseMotion(String name) {
        if (name == null) {
            return MOTION_STILL;
        }
        return switch (name.trim().toLowerCase()) {
            case "inward" -> MOTION_INWARD;
            case "outward" -> MOTION_OUTWARD;
            case "up" -> MOTION_UP;
            case "swirl" -> MOTION_SWIRL;
            default -> MOTION_STILL;
        };
    }

    /** Parse a "#RRGGBB" (or "RRGGBB") hex string into a Color; falls back to ender purple. */
    private Color parseColor(String hex) {
        if (hex != null) {
            try {
                return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF);
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return Color.fromRGB(0x9D4EDD);
    }

    private Particle parseParticle(String name, Particle fallback) {
        if (name != null) {
            try {
                return Particle.valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return fallback;
    }
}
