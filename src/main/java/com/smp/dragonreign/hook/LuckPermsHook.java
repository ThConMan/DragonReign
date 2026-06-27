package com.smp.dragonreign.hook;

import com.smp.dragonreign.DragonReign;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.MetaNode;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Optional, soft LuckPerms hook. When the server runs LuckPerms and the feature is
 * turned on, a victor's title is also written as a LuckPerms meta value
 * ("dragonreign-title") so prefix setups built around LuckPerms can show it too.
 *
 * <p>Everything is guarded: if LuckPerms isn't installed, or the toggle is off, or any
 * call fails, the hook quietly does nothing. It is off by default so it can't overwrite
 * an existing prefix setup unless the server opts in.
 */
public final class LuckPermsHook {

    private static final String META_KEY = "dragonreign-title";

    private final DragonReign plugin;
    private LuckPerms api;
    private boolean tried;

    public LuckPermsHook(DragonReign plugin) {
        this.plugin = plugin;
    }

    private boolean available() {
        if (!plugin.config().isVictorLuckPermsMeta()) {
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return false;
        }
        if (api == null && !tried) {
            tried = true;
            try {
                api = LuckPermsProvider.get();
            } catch (Throwable t) {
                api = null;
            }
        }
        return api != null;
    }

    /** Set (or replace) the player's title meta value. */
    public void setTitle(UUID uuid, String value) {
        if (uuid == null || value == null || !available()) {
            return;
        }
        try {
            api.getUserManager().modifyUser(uuid, user -> {
                user.data().clear(node -> node instanceof MetaNode
                        && META_KEY.equals(((MetaNode) node).getMetaKey()));
                Node node = MetaNode.builder(META_KEY, value).build();
                user.data().add(node);
            });
        } catch (Throwable t) {
            // A version mismatch or storage hiccup must never break the cosmetic.
        }
    }

    /** Remove the player's title meta value (e.g. when they turn the title off). */
    public void clearTitle(UUID uuid) {
        if (uuid == null || !available()) {
            return;
        }
        try {
            api.getUserManager().modifyUser(uuid, user ->
                    user.data().clear(node -> node instanceof MetaNode
                            && META_KEY.equals(((MetaNode) node).getMetaKey())));
        } catch (Throwable t) {
            // ignore — soft hook
        }
    }
}
