package com.smp.dragonreign.papi;

import com.smp.dragonreign.DragonReign;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Exposes DragonReign's victor title and a couple of related values to PlaceholderAPI.
 * Because TAB, CMI, CustomNameplates, and chat-format plugins all read PlaceholderAPI,
 * registering this is all that's needed to surface a Dragonlord's title on nameplates,
 * in chat, and in the tab list — no plugin-specific code required.
 *
 * <p>Placeholders:
 * <ul>
 *   <li>{@code %dragonreign_title%} — the coloured title when the player is a victor with
 *       their title turned on; empty otherwise.</li>
 *   <li>{@code %dragonreign_is_victor%} — {@code true} / {@code false}.</li>
 *   <li>{@code %dragonreign_reward_tier%} — the egg owner's current reward tier (else 0).</li>
 * </ul>
 */
public final class DragonReignExpansion extends PlaceholderExpansion {

    private final DragonReign plugin;

    public DragonReignExpansion(DragonReign plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "dragonreign";
    }

    @Override
    public String getAuthor() {
        return "ThConMan";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // stay registered across PlaceholderAPI reloads
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }
        switch (params.toLowerCase()) {
            case "title" -> {
                if (player == null) {
                    return "";
                }
                UUID uuid = player.getUniqueId();
                boolean victor = isVictor(player);
                if (victor && plugin.config().isVictorTitleEnabled() && plugin.victors().titleEnabled(uuid)) {
                    // Trailing space so it slots cleanly between a rank prefix and the name
                    // (e.g. "[Rank] Dragonlord Name"); empty for non-victors keeps spacing tidy.
                    return plugin.victors().plainTitle() + " ";
                }
                return "";
            }
            case "is_victor" -> {
                return Boolean.toString(player != null && isVictor(player));
            }
            case "reward_tier" -> {
                UUID owner = plugin.store().getOwner();
                if (player != null && owner != null && owner.equals(player.getUniqueId())) {
                    return Integer.toString(plugin.store().getRewardTier());
                }
                return "0";
            }
            default -> {
                return null; // not ours — let PlaceholderAPI handle it
            }
        }
    }

    private boolean isVictor(OfflinePlayer player) {
        if (plugin.victors().isVictor(player.getUniqueId())) {
            return true;
        }
        Player online = player.getPlayer();
        return online != null && online.hasPermission(com.smp.dragonreign.Perms.VICTOR);
    }
}
