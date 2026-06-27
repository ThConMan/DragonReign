package com.smp.dragonreign;

/**
 * Every permission node DragonReign checks, in one place so the command, GUIs, and
 * plugin.yml never drift apart. The {@code command.*} and {@code gui.*} nodes are all
 * rolled up under {@link #ADMIN} via plugin.yml children, so granting that one node is
 * still enough for a full admin — but a server can hand out any single node on its own
 * (e.g. give a helper only {@link #HISTORY}).
 */
public final class Perms {

    private Perms() {
    }

    /** Ignores all egg protections (containers, drops, bundles, sweeps). */
    public static final String BYPASS = "dragonreign.bypass";
    /** Use /giveegg. Default true — it's a player-facing feature. */
    public static final String GIVEEGG = "dragonreign.giveegg";

    /** /dr info — the friendly, player-facing egg readout. Default true. */
    public static final String INFO = "dragonreign.command.info";
    /** /dr admininfo — the technical staff readout (location, timers, strict-ownership, inbox). */
    public static final String ADMININFO = "dragonreign.command.admininfo";
    public static final String GUI = "dragonreign.command.gui";
    public static final String HISTORY = "dragonreign.command.history";
    public static final String INBOX = "dragonreign.command.inbox";
    public static final String RESPAWN = "dragonreign.command.respawn";
    public static final String CANCEL = "dragonreign.command.cancel";
    public static final String RELOAD = "dragonreign.command.reload";
    /** Use the teleport-to-egg button in the config GUI. */
    public static final String TELEPORT = "dragonreign.gui.teleport";

    /** Admins grant/revoke Dragonlord status. Child of {@link #ADMIN}. */
    public static final String CMD_VICTOR = "dragonreign.command.victor";
    /** Player-facing cosmetic toggles (/dr particle, /dr title, /dr cosmetics). Default true. */
    public static final String COSMETICS = "dragonreign.command.cosmetics";
    /** Get the in-game guide book (/dr guide). Default true. */
    public static final String GUIDE = "dragonreign.command.guide";

    /**
     * Marks a player as a Dragonlord (victor). Earned automatically at the hold-time
     * threshold, or assigned explicitly. Intentionally NOT registered in plugin.yml so
     * LuckPerms wildcards ('*' / 'dragonreign.*') and plain OP do not auto-confer it —
     * only an explicit grant or the earned threshold counts.
     */
    public static final String VICTOR = "dragonreign.victor";

    /** Parent node; in plugin.yml it grants every command.* / gui.* node above. */
    public static final String ADMIN = "dragonreign.admin";
}
