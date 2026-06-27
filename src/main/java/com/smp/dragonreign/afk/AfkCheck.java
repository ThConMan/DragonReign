package com.smp.dragonreign.afk;

import com.smp.dragonreign.DragonReign;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Works out whether the egg's keeper is sitting idle, so reward and victor time only
 * count while they're actually playing.
 *
 * <p>The keeper is treated as away if ANY available signal says so (a union, not a
 * first-wins short-circuit): CMI's flag, EssentialsX's flag, OR the built-in movement
 * sampler — and the built-in sampler always runs, so a stationary auto-clicker that keeps
 * a CMI/Essentials "active" flag alive is still caught by the no-real-movement check. The
 * plugin hooks are reached purely by reflection and wrapped in try/catch, so a missing or
 * different-version plugin can never break anything. There is exactly one egg, so the
 * built-in sampler only ever tracks one player.
 *
 * <p>The built-in sampler requires sustained travel (net displacement past a threshold),
 * not just any block change, so AFK pools, tiny ice/boat loops, and one-block nudge macros
 * no longer read as "active". Look-direction is deliberately ignored — counting it would
 * let a mouse-jiggler defeat the check.
 */
public final class AfkCheck {

    private final DragonReign plugin;

    // Cached reflection handles, resolved on first successful use and reused after.
    private Object cmiInstance;
    private Method cmiGetPlayerManager;
    private Method cmiGetUser;
    private Method cmiUserIsAfk;
    private boolean cmiResolved;
    private boolean cmiBroken;

    private Object essentials;
    private Method essGetUser;
    private Method essUserIsAfk;
    private boolean essResolved;
    private boolean essBroken;

    // How far (in blocks) the keeper must travel from their last anchor for the built-in
    // sampler to count it as real movement. Below this, periodic micro-motion (water
    // bobbing, a 2-block loop, a 1-block nudge macro) reads as standing still.
    private static final double MOVE_THRESHOLD = 3.0;
    private static final double MOVE_THRESHOLD_SQ = MOVE_THRESHOLD * MOVE_THRESHOLD;

    // Built-in sampler state (single keeper at a time). The anchor is the last spot we
    // considered "moved to"; idle time is measured from when we last reached it.
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private boolean haveAnchor;
    private long lastMoveMillis;

    public AfkCheck(DragonReign plugin) {
        this.plugin = plugin;
    }

    /** Forget the built-in sampler's tracked position — call this when the egg changes hands. */
    public void reset() {
        haveAnchor = false;
        lastMoveMillis = 0L;
    }

    /**
     * True if this player should NOT earn hold time right now. Away if ANY available
     * detector says away — and the built-in sampler is always evaluated so a no-movement
     * auto-clicker is caught even when CMI/Essentials report the player as active.
     */
    public boolean isAfk(Player player) {
        if (player == null || !plugin.config().isAfkEnabled()) {
            return false;
        }
        boolean away = false;
        Boolean cmi = cmiAfk(player);
        if (Boolean.TRUE.equals(cmi)) {
            away = true;
        }
        Boolean ess = essentialsAfk(player);
        if (Boolean.TRUE.equals(ess)) {
            away = true;
        }
        // Always sample movement (also keeps the anchor current), then OR it in.
        if (builtinAfk(player)) {
            away = true;
        }
        return away;
    }

    // ── CMI ────────────────────────────────────────────────────────────────────

    private Boolean cmiAfk(Player player) {
        if (cmiBroken) {
            return null;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("CMI")) {
            return null;
        }
        try {
            if (!cmiResolved) {
                Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI");
                cmiInstance = cmiClass.getMethod("getInstance").invoke(null);
                cmiGetPlayerManager = cmiInstance.getClass().getMethod("getPlayerManager");
                cmiResolved = true;
            }
            Object playerManager = cmiGetPlayerManager.invoke(cmiInstance);
            if (cmiGetUser == null) {
                cmiGetUser = playerManager.getClass().getMethod("getUser", Player.class);
            }
            Object user = cmiGetUser.invoke(playerManager, player);
            if (user == null) {
                return null;
            }
            if (cmiUserIsAfk == null) {
                cmiUserIsAfk = user.getClass().getMethod("isAfk");
            }
            return (Boolean) cmiUserIsAfk.invoke(user);
        } catch (Throwable t) {
            cmiBroken = true; // wrong version / API moved — stop trying CMI this session
            return null;
        }
    }

    // ── EssentialsX ──────────────────────────────────────────────────────────────

    private Boolean essentialsAfk(Player player) {
        if (essBroken) {
            return null;
        }
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess == null || !ess.isEnabled()) {
            return null;
        }
        try {
            if (!essResolved) {
                essentials = ess;
                essGetUser = ess.getClass().getMethod("getUser", Player.class);
                essResolved = true;
            }
            Object user = essGetUser.invoke(essentials, player);
            if (user == null) {
                return null;
            }
            if (essUserIsAfk == null) {
                essUserIsAfk = user.getClass().getMethod("isAfk");
            }
            return (Boolean) essUserIsAfk.invoke(user);
        } catch (Throwable t) {
            essBroken = true;
            return null;
        }
    }

    // ── Built-in fallback ────────────────────────────────────────────────────────

    /**
     * Sample the keeper's position once per accrual tick (no movement events). They only
     * count as "moved" once they've travelled {@link #MOVE_THRESHOLD} blocks from their
     * anchor; until then any periodic micro-motion is ignored. If they haven't made real
     * progress for idle-seconds, count them as away.
     */
    private boolean builtinAfk(Player player) {
        Location loc = player.getLocation();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        long now = System.currentTimeMillis();

        if (!haveAnchor) {
            anchorX = x;
            anchorY = y;
            anchorZ = z;
            haveAnchor = true;
            lastMoveMillis = now;
            return false;
        }
        double dx = x - anchorX;
        double dy = y - anchorY;
        double dz = z - anchorZ;
        if (dx * dx + dy * dy + dz * dz >= MOVE_THRESHOLD_SQ) {
            anchorX = x;
            anchorY = y;
            anchorZ = z;
            lastMoveMillis = now;
            return false;
        }
        long idleMillis = plugin.config().getAfkIdleSeconds() * 1000L;
        return now - lastMoveMillis >= idleMillis;
    }
}
