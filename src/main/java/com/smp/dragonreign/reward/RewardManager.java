package com.smp.dragonreign.reward;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.store.EggDataStore;
import com.smp.dragonreign.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Rewards the current keeper for every interval-minutes of active held time. The held
 * time itself is measured once, elsewhere (the hold-time ticker), and fed here as a
 * delta. Each completed interval runs that tier's console commands and tells only the
 * holder. There are no server-wide or milestone broadcasts.
 *
 * <p>The tier and the banked progress live on the egg state (so they persist and reset
 * with the egg). Past the last configured tier, the final tier keeps paying out, so a
 * very long hold keeps earning.
 */
public final class RewardManager {

    /** Optional Geyser/Floodgate prefix (".") plus a vanilla-legal name. Anything else is
     *  refused before substitution into a console command. */
    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("^\\.?[A-Za-z0-9_]{1,16}$");

    private final DragonReign plugin;

    public RewardManager(DragonReign plugin) {
        this.plugin = plugin;
    }

    /** Bank active held time for the keeper and pay out any intervals it completed. */
    public void addActive(UUID owner, long deltaMillis) {
        if (owner == null || deltaMillis <= 0) {
            return;
        }
        ConfigManager c = plugin.config();
        if (!c.isRewardsEnabled()) {
            return;
        }
        List<List<String>> tiers = c.getRewardTiers();
        if (tiers.isEmpty()) {
            return; // nothing configured to give
        }
        EggDataStore store = plugin.store();
        long intervalMillis = c.getRewardIntervalMinutes() * 60_000L;

        long progress = store.getRewardProgressMillis() + deltaMillis;
        int tier = store.getRewardTier();

        // Pay out every whole interval the new progress completed (usually 0 or 1).
        while (progress >= intervalMillis) {
            progress -= intervalMillis;
            deliver(owner, tier, tiers);
            tier++;
        }
        store.setRewardProgressMillis(progress);
        store.setRewardTier(tier);
    }

    /**
     * Run the commands for the tier the keeper just completed. {@code tierIndex} is the
     * zero-based count of rewards already earned; we clamp it to the last configured tier
     * so long holders keep getting the top reward.
     */
    private void deliver(UUID owner, int tierIndex, List<List<String>> tiers) {
        int idx = Math.min(tierIndex, tiers.size() - 1);
        int human = tierIndex + 1; // 1-based for display / commands
        Player player = Bukkit.getPlayer(owner);
        if (player == null) {
            return; // only an online keeper earns; never splice a raw UUID into a command
        }
        String playerName = player.getName();
        // The name is substituted into a console command run as CONSOLE. Vanilla names are
        // safe, but a Bedrock/Geyser name (when the proxy keeps spaces) could inject extra
        // command arguments. Refuse anything outside the safe charset rather than risk it.
        if (!SAFE_NAME.matcher(playerName).matches()) {
            plugin.getLogger().warning("Skipping hold reward: keeper name '" + playerName
                    + "' has characters that are unsafe inside a console command.");
            return;
        }

        for (String raw : tiers.get(idx)) {
            String cmd = raw.replace("%player%", playerName).replace("%tier%", Integer.toString(human));
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Hold-reward command failed (" + cmd + "): " + t.getMessage());
            }
        }

        if (player != null) {
            String msg = plugin.config().getRewardEarnedMessage().replace("<tier>", Integer.toString(human));
            player.sendMessage(Msg.prefixed(plugin.config().getPrefix(), msg));
        }
        plugin.history().append(EventType.REWARD_EARNED, player, null,
                "earned hold reward #" + human);
    }
}
