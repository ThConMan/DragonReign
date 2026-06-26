package com.smp.dragonreign.model;

import org.bukkit.Material;

/**
 * Every meaningful thing that can happen to the egg. Each type carries a short
 * label for the history GUI and an icon material so the log reads at a glance.
 */
public enum EventType {

    PLACED("Placed", Material.DRAGON_EGG),
    BROKEN("Broken", Material.IRON_PICKAXE),
    PICKED_UP("Picked up", Material.LIME_DYE),
    OWNER_CHANGED("Owner changed", Material.PLAYER_HEAD),
    BLOCKED_CONTAINER("Container blocked", Material.CHEST),
    BLOCKED_BUNDLE("Bundle blocked", Material.BUNDLE),
    BLOCKED_DROP("Drop blocked", Material.BARRIER),
    ENDERCHEST_RETURN("Ender chest sweep", Material.ENDER_CHEST),
    RESPAWN_TRIGGERED("Respawn triggered", Material.DRAGON_HEAD),
    RESPAWN_COUNTDOWN_STARTED("Countdown started", Material.CLOCK),
    RESPAWN_COUNTDOWN_ABORTED("Countdown aborted", Material.BELL),
    EGG_ERASED("Egg erased", Material.TNT),
    EGG_SPAWNED("Egg spawned", Material.END_PORTAL_FRAME),
    TRANSFER("Transfer", Material.NAME_TAG),
    ADMIN("Admin action", Material.COMMAND_BLOCK);

    private final String label;
    private final Material icon;

    EventType(String label, Material icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public Material icon() {
        return icon;
    }
}
