package com.smp.dragonreign.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** MiniMessage rendering, the prefixed action-bar nudge, and per-key throttling. */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // key -> last time we let an action through, for throttling spammy block events.
    private static final Map<String, Long> THROTTLE = new ConcurrentHashMap<>();

    private Msg() {
    }

    public static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage);
    }

    public static Component prefixed(String prefix, String miniMessage) {
        return MM.deserialize((prefix == null ? "" : prefix) + (miniMessage == null ? "" : miniMessage));
    }

    /** Quiet feedback that the egg won't budge — uses the action bar so it doesn't flood chat. */
    public static void nudge(Player player, Component component) {
        player.sendActionBar(component);
    }

    /**
     * Returns true if an action keyed by {@code key} happened within the last
     * {@code windowMillis}, i.e. the caller should suppress it. Otherwise records
     * "now" and returns false (let it through).
     */
    public static boolean throttled(String key, long windowMillis) {
        long now = System.currentTimeMillis();
        Long last = THROTTLE.get(key);
        if (last != null && now - last < windowMillis) {
            return true;
        }
        THROTTLE.put(key, now);
        return false;
    }

    public static void clearThrottle() {
        THROTTLE.clear();
    }

    /**
     * MiniMessage treats '&lt;' as the start of a tag, so any free-text we splice into
     * a message (player names, detail strings) must neutralize it or a stray '&lt;'
     * silently eats the rest of the line. Cheap and safe to call on anything.
     */
    public static String escape(String s) {
        return s == null ? "" : s.replace("<", "\\<");
    }
}
