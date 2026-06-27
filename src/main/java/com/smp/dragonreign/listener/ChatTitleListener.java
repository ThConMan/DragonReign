package com.smp.dragonreign.listener;

import com.smp.dragonreign.DragonReign;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Puts the Dragonlord title into a victor's chat line.
 *
 * <p>Some chat setups don't expand the {@code %dragonreign_title%} placeholder — CMI's
 * display name is the common one. For those, turning on {@code chat.enabled} lets the
 * plugin rebuild the victor's chat name itself ({@code prefix + title + name + suffix})
 * using the configured format. Only victors with their title on are rebuilt; every other
 * player's chat keeps the server's normal formatting, so this can't disturb existing chat.
 */
public final class ChatTitleListener implements Listener {

    // Parses the section-sign output of translateAlternateColorCodes, including &x hex.
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(ChatColor.COLOR_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final DragonReign plugin;

    public ChatTitleListener(DragonReign plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.config().isChatEnabled() || !plugin.config().isVictorTitleEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.victors().isVictor(player) || !plugin.victors().titleEnabled(player.getUniqueId())) {
            return; // leave non-victors (and title-off victors) on the server's normal format
        }

        String fmt = plugin.config().getChatFormat();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            fmt = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, fmt);
        }
        fmt = fmt.replace("{title}", plugin.config().getVictorTitle())
                 .replace("{name}", player.getName());
        final Component name = LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', fmt));

        // Rebuild only the name portion; the typed message keeps its own formatting.
        event.renderer((source, sourceDisplayName, message, viewer) -> name.append(message));
    }
}
