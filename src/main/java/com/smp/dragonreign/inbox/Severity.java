package com.smp.dragonreign.inbox;

import org.bukkit.Material;

/**
 * How loud an inbox alert is. The colour feeds MiniMessage in chat and the GUI; the
 * icon material gives each severity a recognizable face in the inbox browser.
 */
public enum Severity {

    INFO("green", Material.LIME_DYE),
    WARN("gold", Material.ORANGE_DYE),
    CRITICAL("red", Material.REDSTONE);

    private final String color;
    private final Material icon;

    Severity(String color, Material icon) {
        this.color = color;
        this.icon = icon;
    }

    /** Bare MiniMessage colour name, e.g. {@code "gold"} → wrap as {@code <gold>…</gold>}. */
    public String color() {
        return color;
    }

    public Material icon() {
        return icon;
    }

    public static Severity parse(String raw) {
        if (raw == null) {
            return INFO;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return INFO;
        }
    }
}
