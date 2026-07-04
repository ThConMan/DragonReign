package com.smp.dragonreign.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional, reflection-only bridge to the sibling Anonymizer plugin.
 *
 * <p>While Anonymizer is hiding an invisible player, DragonReign suppresses that player's
 * victor title so it can't out them — the title would otherwise still render next to a
 * redacted name in chat (CMI chat format is {@code {prefix}%dragonreign_title%{nickName}},
 * and the title is a separate token Anonymizer's prefix-blanking can't reach).
 *
 * <p>Pure reflection: DragonReign neither depends on nor compiles against Anonymizer (which is
 * closed-source), and this no-ops cleanly — returning {@code false} — when Anonymizer is absent.
 * Handles are resolved once and cached; Anonymizer's {@code anonManager()} instance is stable
 * across its config reloads, so the cached reference stays valid.
 */
public final class AnonymizerHook {

    private static Object anonManager;   // Anonymizer#anonManager() -> AnonManager
    private static Method isAnonymized;  // AnonManager#isAnonymized(UUID) -> boolean
    private static boolean resolved;
    private static boolean available;

    private AnonymizerHook() {}

    private static void resolve() {
        resolved = true; // resolve once; placeholders only fire after both plugins have enabled
        try {
            Plugin anon = Bukkit.getPluginManager().getPlugin("Anonymizer");
            if (anon == null || !anon.isEnabled()) return; // Anonymizer absent → available stays false
            anonManager = anon.getClass().getMethod("anonManager").invoke(anon);
            isAnonymized = anonManager.getClass().getMethod("isAnonymized", UUID.class);
            available = true;
        } catch (Throwable ignored) {
            available = false; // wrong/old Anonymizer version — stay silent, just don't blank
        }
    }

    /**
     * @return true only if Anonymizer is present, enabled, and currently anonymizing this player.
     *         false if Anonymizer is absent or the player isn't hidden.
     */
    public static boolean isAnonymized(UUID id) {
        if (!resolved) resolve();
        if (!available) return false;
        try {
            return Boolean.TRUE.equals(isAnonymized.invoke(anonManager, id));
        } catch (Throwable t) {
            return false;
        }
    }
}
